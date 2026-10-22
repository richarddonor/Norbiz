package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.ApplicationRequest;
import com.chardizard.Norbiz.dto.CustomerPaymentRequest;
import com.chardizard.Norbiz.dto.ReceivedTenderRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import com.chardizard.Norbiz.security.CompanyAccess;
import com.chardizard.Norbiz.util.DateRangeUtils;
import com.chardizard.Norbiz.util.SpecificationUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Customer Payment (docs/ACCOUNTING.md "Customer payments"): money received from a customer as one or more tenders.
 * Each tender opens a RECEIVABLE CREDIT; the request's applications pay open charges right away and whatever is left
 * is an advance. Checks stay pending (their applications count as PDC on the charges) until deposited and cleared;
 * a bounced check reverses everything it paid (ReceivedCheckService).
 */
@Service
@RequiredArgsConstructor
public class CustomerPaymentService {

    private static final Logger log = LoggerFactory.getLogger(CustomerPaymentService.class);
    private static final TransactionType TYPE = TransactionType.CUSTOMER_PAYMENT;
    private static final String REFERENCE_PREFIX = "CPY";

    private final CustomerPaymentRepository paymentRepository;
    private final CustomerPaymentTenderRepository tenderRepository;
    private final CompanyRepository companyRepository;
    private final CustomerRepository customerRepository;
    private final BankRepository bankRepository;
    private final BankAccountRepository bankAccountRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;
    private final AccountingSettingsService accountingSettingsService;
    private final OpenItemService openItemService;
    private final BankEntryService bankEntryService;
    private final CompanyAccess companyAccess;

    public Page<CustomerPayment> findAllForUser(String username, Long customerId, Map<String, String> filters,
                                                Instant dateFrom, Instant dateTo, Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        Specification<CustomerPayment> companyScope = companyIds == null ? null : (root, query, cb) -> root.get("company").get("id").in(companyIds);

        return paymentRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                customerId == null ? null : (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId),
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.containsIgnoreCase("customer.name", filters.get("customer")),
                SpecificationUtils.containsIgnoreCase("remarks", filters.get("remarks")),
                SpecificationUtils.anyContainsIgnoreCase(filters.get("checkNumber"), "tenders.checkNumber"),
                SpecificationUtils.anyContainsIgnoreCase(filters.get("receiptNumber"), "tenders.officialReceiptNumber",
                        "tenders.provisionalReceiptNumber"),
                SpecificationUtils.booleanEquals("voided", filters.get("voided")),
                SpecificationUtils.dateRange("paymentDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        ), pageable);
    }

    public CustomerPayment findById(Long id, String username) {
        CustomerPayment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Customer payment not found: " + id));
        companyAccess.assertCompanyAccess(username, payment.getCompany().getId());
        return payment;
    }

    @Transactional
    public CustomerPayment create(CustomerPaymentRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));
        companyAccess.assertCompanyAccess(username, company.getId());

        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + request.getCustomerId()));
        if (!customer.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Customer does not belong to company: " + company.getId());
        }
        if (!customer.isActive()) {
            throw new IllegalArgumentException("Customer is inactive: " + customer.getName());
        }
        Instant paymentDate = DateRangeUtils.startOfDayUtc(request.getPaymentDate());
        accountingSettingsService.assertOnOrAfterStart(company, paymentDate, "Payment");

        Instant now = Instant.now();
        CustomerPayment payment = new CustomerPayment();
        payment.setCompany(company);
        payment.setCustomer(customer);
        payment.setPaymentDate(paymentDate);
        payment.setSheetNumber(request.getSheetNumber());
        payment.setRemarks(request.getRemarks());
        payment.setCreatedAt(now);
        payment.setCreatedBy(username);

        BigDecimal total = BigDecimal.ZERO;
        int lineNumber = 1;
        for (ReceivedTenderRequest t : request.getTenders()) {
            CustomerPaymentTender tender = buildTender(t, company, customer, paymentDate);
            tender.setCustomerPayment(payment);
            tender.setLineNumber(lineNumber++);
            payment.getTenders().add(tender);
            total = total.add(t.getAmount());
        }
        payment.setTotalAmount(total);

        List<ApplicationRequest> applications = request.getApplications() != null ? request.getApplications() : List.of();
        List<OpenItem> charges = new ArrayList<>();
        for (ApplicationRequest application : applications) {
            charges.add(openItemService.require(application.getOpenItemId(), company));
        }

        // Generated last, only once validation has fully passed.
        payment.setReferenceNumber(transactionReferenceService.next(company.getId(), TYPE.name(), REFERENCE_PREFIX));
        CustomerPayment saved = paymentRepository.save(payment);
        transactionEventService.recordSystemEvent(company, TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        List<OpenItem> tenderItems = new ArrayList<>();
        for (CustomerPaymentTender tender : saved.getTenders()) {
            OpenItem item = openItemService.open(company, AccountSide.RECEIVABLE, customer, null, OpenItemDirection.CREDIT,
                    OpenItemSourceType.CUSTOMER_PAYMENT_TENDER, tender.getId(), tenderReference(saved, tender), paymentDate, null,
                    tender.getAmount(), tender.getMode() != ReceivedPaymentMode.CHECK, username);
            tender.setOpenItem(item);
            tenderItems.add(item);
            if (tender.getMode() == ReceivedPaymentMode.BANK_TRANSFER || tender.getMode() == ReceivedPaymentMode.CREDIT_CARD) {
                bankEntryService.post(company, tender.getDepositBankAccount(), paymentDate, BankEntryType.TRANSFER_RECEIPT, tender.getAmount(),
                        TYPE, saved.getId(), tender.getId(), saved.getReferenceNumber(), tender.getCheckNumber(), null, customer.getName(), username);
            }
            if (tender.getReplacesTender() != null) recordReplacement(tender, username);
        }

        // Settled tenders first, then checks by check date (the earliest to clear pays first).
        List<Integer> autoOrder = IntStream.range(0, saved.getTenders().size()).boxed()
                .sorted(Comparator.comparing((Integer i) -> saved.getTenders().get(i).getMode() == ReceivedPaymentMode.CHECK)
                        .thenComparing(i -> saved.getTenders().get(i).getCheckDate(), Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(i -> i))
                .toList();
        TenderApplications.apply(openItemService, tenderItems, autoOrder, applications, charges, paymentDate, TYPE, saved.getId(), username);

        log.info("User '{}' posted customer payment (id={}) {} for {} from customer {} with {} tender(s), {} application(s)", username,
                saved.getId(), saved.getReferenceNumber(), total.toPlainString(), customer.getId(), saved.getTenders().size(), applications.size());
        return saved;
    }

    /**
     * Cancels a payment posted in error. Every cash/check tender must still be on hand (not deposited, cleared or
     * bounced), and nothing posted later may have applied its credit (Customer Credit Application, Refund).
     */
    @Transactional
    public CustomerPayment voidPayment(Long id, String username) {
        CustomerPayment payment = findById(id, username);
        if (payment.isVoided()) {
            throw new IllegalArgumentException("Customer payment already voided: " + id);
        }
        for (CustomerPaymentTender tender : payment.getTenders()) {
            boolean settledOnReceipt = tender.getMode() != ReceivedPaymentMode.CASH && tender.getMode() != ReceivedPaymentMode.CHECK;
            if (!settledOnReceipt && tender.getStatus() != ReceivedCheckStatus.ON_HAND) {
                throw new IllegalArgumentException("Cannot void " + payment.getReferenceNumber() + ": its " + describe(tender) + " is "
                        + tender.getStatus() + (tender.getDeposit() != null ? " (deposit " + tender.getDeposit().getReferenceNumber() + ")" : ""));
            }
            if (openItemService.appliedByOthers(tender.getOpenItem(), TYPE, payment.getId())) {
                throw new IllegalArgumentException("Cannot void " + payment.getReferenceNumber() + ": its " + describe(tender)
                        + " has been applied or refunded by later documents — void those first");
            }
        }
        Instant now = Instant.now();
        openItemService.reverseBySource(TYPE, payment.getId(), AllocationReversalReason.VOIDED, payment.getPaymentDate(), username);
        for (CustomerPaymentTender tender : payment.getTenders()) {
            openItemService.voidItem(tender.getOpenItem(), username);
            if (tender.getReplacesTender() != null) undoReplacement(tender, username);
        }
        bankEntryService.voidBySource(TYPE, payment.getId(), username);

        payment.setVoided(true);
        payment.setVoidedAt(now);
        payment.setVoidedBy(username);
        CustomerPayment saved = paymentRepository.save(payment);
        transactionEventService.recordSystemEvent(saved.getCompany(), TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided customer payment (id={})", username, id);
        return saved;
    }

    private CustomerPaymentTender buildTender(ReceivedTenderRequest t, Company company, Customer customer, Instant paymentDate) {
        CustomerPaymentTender tender = new CustomerPaymentTender();
        tender.setMode(t.getMode());
        tender.setAmount(t.getAmount());
        tender.setCheckNumber(t.getCheckNumber());
        tender.setOfficialReceiptNumber(t.getOfficialReceiptNumber());
        tender.setProvisionalReceiptNumber(t.getProvisionalReceiptNumber());
        tender.setStatusDate(paymentDate);
        switch (t.getMode()) {
            case CHECK -> {
                if (!StringUtils.hasText(t.getCheckNumber()) || t.getCheckDate() == null || t.getBankId() == null) {
                    throw new IllegalArgumentException("A CHECK tender needs checkNumber, checkDate and bankId");
                }
                Bank bank = bankRepository.findById(t.getBankId())
                        .orElseThrow(() -> new IllegalArgumentException("Bank not found: " + t.getBankId()));
                if (!bank.getCompany().getId().equals(company.getId())) {
                    throw new IllegalArgumentException("Bank does not belong to company: " + company.getId());
                }
                if (!bank.isActive()) {
                    throw new IllegalArgumentException("Bank is inactive: " + bank.getName());
                }
                tender.setBank(bank);
                tender.setCheckDate(DateRangeUtils.startOfDayUtc(t.getCheckDate()));
                tender.setStatus(ReceivedCheckStatus.ON_HAND);
            }
            case CASH -> tender.setStatus(ReceivedCheckStatus.ON_HAND);
            case BANK_TRANSFER, CREDIT_CARD -> {
                if (t.getDepositBankAccountId() == null) {
                    throw new IllegalArgumentException("A " + t.getMode() + " tender needs depositBankAccountId (the account it landed in)");
                }
                BankAccount account = bankAccountRepository.findById(t.getDepositBankAccountId())
                        .orElseThrow(() -> new IllegalArgumentException("Bank account not found: " + t.getDepositBankAccountId()));
                BankEntryService.assertUsable(account, company);
                tender.setDepositBankAccount(account);
                tender.setStatus(ReceivedCheckStatus.CLEARED);
            }
            case OTHERS -> tender.setStatus(ReceivedCheckStatus.CLEARED);
        }
        if (t.getMode() != ReceivedPaymentMode.BANK_TRANSFER && t.getMode() != ReceivedPaymentMode.CREDIT_CARD && t.getDepositBankAccountId() != null) {
            throw new IllegalArgumentException("depositBankAccountId applies to BANK_TRANSFER/CREDIT_CARD tenders only — cash and checks reach "
                    + "the bank through a Deposit");
        }
        if (t.getReplacesTenderId() != null) {
            CustomerPaymentTender replaced = tenderRepository.findById(t.getReplacesTenderId())
                    .orElseThrow(() -> new IllegalArgumentException("Tender not found: " + t.getReplacesTenderId()));
            if (!replaced.getCustomerPayment().getCustomer().getId().equals(customer.getId())) {
                throw new IllegalArgumentException("replacesTenderId belongs to a different customer");
            }
            if (replaced.getStatus() != ReceivedCheckStatus.BOUNCED) {
                throw new IllegalArgumentException("Only a BOUNCED check can be replaced; " + describe(replaced) + " is " + replaced.getStatus());
            }
            tender.setReplacesTender(replaced);
        }
        return tender;
    }

    // A replacement tender covers (part of) a bounced one; once fully covered it is REPLACED.
    private void recordReplacement(CustomerPaymentTender tender, String username) {
        CustomerPaymentTender replaced = tender.getReplacesTender();
        replaced.setReplacedAmount(replaced.getReplacedAmount().add(tender.getAmount()));
        if (replaced.getReplacedAmount().compareTo(replaced.getAmount()) >= 0) {
            replaced.setStatus(ReceivedCheckStatus.REPLACED);
            replaced.setStatusDate(tender.getCustomerPayment().getPaymentDate());
        }
        CustomerPayment original = replaced.getCustomerPayment();
        transactionEventService.recordSystemEvent(original.getCompany(), TYPE, original.getId(), original.getReferenceNumber(),
                TransactionEventType.STATUS_CHANGED, username, Instant.now(), describe(replaced) + " replaced by "
                        + tender.getCustomerPayment().getReferenceNumber() + " (" + replaced.getReplacedAmount().toPlainString() + " of "
                        + replaced.getAmount().toPlainString() + " covered)");
    }

    private void undoReplacement(CustomerPaymentTender tender, String username) {
        CustomerPaymentTender replaced = tender.getReplacesTender();
        replaced.setReplacedAmount(replaced.getReplacedAmount().subtract(tender.getAmount()));
        if (replaced.getStatus() == ReceivedCheckStatus.REPLACED && replaced.getReplacedAmount().compareTo(replaced.getAmount()) < 0) {
            replaced.setStatus(ReceivedCheckStatus.BOUNCED);
        }
        CustomerPayment original = replaced.getCustomerPayment();
        transactionEventService.recordSystemEvent(original.getCompany(), TYPE, original.getId(), original.getReferenceNumber(),
                TransactionEventType.STATUS_CHANGED, username, Instant.now(), describe(replaced) + " replacement "
                        + tender.getCustomerPayment().getReferenceNumber() + " voided");
    }

    static String tenderReference(CustomerPayment payment, CustomerPaymentTender tender) {
        return payment.getReferenceNumber() + (tender.getMode() == ReceivedPaymentMode.CHECK ? " #" + tender.getCheckNumber()
                : payment.getTenders().size() > 1 ? " (" + tender.getMode() + " " + tender.getLineNumber() + ")" : "");
    }

    static String describe(CustomerPaymentTender tender) {
        return tender.getMode() == ReceivedPaymentMode.CHECK ? "check #" + tender.getCheckNumber()
                : tender.getMode().name().toLowerCase().replace('_', ' ') + " tender " + tender.getLineNumber();
    }
}
