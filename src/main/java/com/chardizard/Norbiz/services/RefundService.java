package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.CustomerRefundRequest;
import com.chardizard.Norbiz.dto.RefundLineRequest;
import com.chardizard.Norbiz.dto.SupplierRefundRequest;
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
import java.util.List;
import java.util.Map;

/**
 * Refunds (docs/ACCOUNTING.md "Refunds"). A Customer Refund pays a customer back unused credit (an advance or credit
 * memo); a Supplier Refund records a supplier paying us back unused credit (an advance we paid, or a supplier debit
 * memo). Either opens a CHARGE open item that the selected credits are fully applied to, and moves the money through
 * one bank account. Voiding reverses both.
 */
@Service
@RequiredArgsConstructor
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final CustomerRefundRepository customerRefundRepository;
    private final SupplierRefundRepository supplierRefundRepository;
    private final CompanyRepository companyRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final BankAccountRepository bankAccountRepository;
    private final BankRepository bankRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;
    private final AccountingSettingsService accountingSettingsService;
    private final OpenItemService openItemService;
    private final BankEntryService bankEntryService;
    private final CompanyAccess companyAccess;

    // ---- customer refunds ----

    public Page<CustomerRefund> findCustomerRefunds(String username, Long customerId, Map<String, String> filters, Instant dateFrom,
                                                    Instant dateTo, Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        Specification<CustomerRefund> companyScope = companyIds == null ? null : (root, query, cb) -> root.get("company").get("id").in(companyIds);
        return customerRefundRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                customerId == null ? null : (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId),
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.containsIgnoreCase("customer.name", filters.get("customer")),
                SpecificationUtils.containsIgnoreCase("checkNumber", filters.get("checkNumber")),
                SpecificationUtils.booleanEquals("voided", filters.get("voided")),
                SpecificationUtils.dateRange("refundDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        ), pageable);
    }

    public CustomerRefund findCustomerRefund(Long id, String username) {
        CustomerRefund refund = customerRefundRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Customer refund not found: " + id));
        companyAccess.assertCompanyAccess(username, refund.getCompany().getId());
        return refund;
    }

    @Transactional
    public CustomerRefund createCustomerRefund(CustomerRefundRequest request, String username) {
        Company company = loadCompany(request.getCompanyId(), username);
        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + request.getCustomerId()));
        if (!customer.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Customer does not belong to company: " + company.getId());
        }
        Instant refundDate = DateRangeUtils.startOfDayUtc(request.getRefundDate());
        accountingSettingsService.assertOnOrAfterStart(company, refundDate, "Refund");

        CustomerRefund refund = new CustomerRefund();
        refund.setCompany(company);
        refund.setCustomer(customer);
        refund.setRefundDate(refundDate);
        refund.setSheetNumber(request.getSheetNumber());
        refund.setRemarks(request.getRemarks());
        refund.setMode(request.getMode());
        refund.setCheckNumber(request.getCheckNumber());
        refund.setStatusDate(refundDate);
        boolean drawnOnAccount = request.getMode() == IssuedPaymentMode.CHECK || request.getMode() == IssuedPaymentMode.BANK_TRANSFER;
        refund.setBankAccount(resolveAccount(request.getBankAccountId(), drawnOnAccount, company, request.getMode().name()));
        if (request.getMode() == IssuedPaymentMode.CHECK) {
            if (!StringUtils.hasText(request.getCheckNumber()) || request.getCheckDate() == null) {
                throw new IllegalArgumentException("A CHECK refund needs checkNumber and checkDate");
            }
            refund.setCheckDate(DateRangeUtils.startOfDayUtc(request.getCheckDate()));
            refund.setStatus(IssuedCheckStatus.ISSUED);
        } else {
            refund.setStatus(IssuedCheckStatus.CLEARED);
        }
        Instant now = Instant.now();
        refund.setCreatedAt(now);
        refund.setCreatedBy(username);

        List<OpenItem> credits = new ArrayList<>();
        BigDecimal amount = BigDecimal.ZERO;
        int lineNumber = 1;
        for (RefundLineRequest l : request.getLines()) {
            OpenItem credit = requireCredit(l.getCreditItemId(), company, AccountSide.RECEIVABLE, customer.getId());
            CustomerRefundLine line = new CustomerRefundLine();
            line.setCustomerRefund(refund);
            line.setCreditItem(credit);
            line.setAmount(l.getAmount());
            line.setLineNumber(lineNumber++);
            refund.getLines().add(line);
            credits.add(credit);
            amount = amount.add(l.getAmount());
        }
        refund.setAmount(amount);

        refund.setReferenceNumber(transactionReferenceService.next(company.getId(), TransactionType.CUSTOMER_REFUND.name(), "CRF"));
        CustomerRefund saved = customerRefundRepository.save(refund);
        transactionEventService.recordSystemEvent(company, TransactionType.CUSTOMER_REFUND, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        OpenItem charge = openItemService.open(company, AccountSide.RECEIVABLE, customer, null, OpenItemDirection.CHARGE,
                OpenItemSourceType.CUSTOMER_REFUND, saved.getId(), saved.getReferenceNumber(), refundDate, refundDate, amount, true, username);
        saved.setOpenItem(charge);
        applyCredits(credits, charge, saved.getLines().stream().map(CustomerRefundLine::getAmount).toList(), refundDate,
                TransactionType.CUSTOMER_REFUND, saved.getId(), username);
        if (drawnOnAccount) {
            bankEntryService.post(company, saved.getBankAccount(), refundDate,
                    request.getMode() == IssuedPaymentMode.CHECK ? BankEntryType.CHECK_DISBURSEMENT : BankEntryType.TRANSFER_DISBURSEMENT,
                    amount.negate(), TransactionType.CUSTOMER_REFUND, saved.getId(), null, saved.getReferenceNumber(), saved.getCheckNumber(),
                    saved.getCheckDate(), "Refund to " + customer.getName(), username);
        }
        log.info("User '{}' posted customer refund (id={}) {} of {} to customer {}", username, saved.getId(), saved.getReferenceNumber(),
                amount.toPlainString(), customer.getId());
        return saved;
    }

    @Transactional
    public CustomerRefund voidCustomerRefund(Long id, String username) {
        CustomerRefund refund = findCustomerRefund(id, username);
        if (refund.isVoided()) {
            throw new IllegalArgumentException("Customer refund already voided: " + id);
        }
        voidRefund(TransactionType.CUSTOMER_REFUND, refund.getId(), refund.getReferenceNumber(), refund.getRefundDate(), refund.getOpenItem(), username);
        Instant now = Instant.now();
        refund.setVoided(true);
        refund.setVoidedAt(now);
        refund.setVoidedBy(username);
        if (refund.getStatus() == IssuedCheckStatus.ISSUED) {
            refund.setStatus(IssuedCheckStatus.CANCELLED);
            refund.setStatusDate(DateRangeUtils.today());
        }
        CustomerRefund saved = customerRefundRepository.save(refund);
        transactionEventService.recordSystemEvent(saved.getCompany(), TransactionType.CUSTOMER_REFUND, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided customer refund (id={})", username, id);
        return saved;
    }

    // ---- supplier refunds ----

    public Page<SupplierRefund> findSupplierRefunds(String username, Long supplierId, Map<String, String> filters, Instant dateFrom,
                                                    Instant dateTo, Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        Specification<SupplierRefund> companyScope = companyIds == null ? null : (root, query, cb) -> root.get("company").get("id").in(companyIds);
        return supplierRefundRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                supplierId == null ? null : (root, query, cb) -> cb.equal(root.get("supplier").get("id"), supplierId),
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.containsIgnoreCase("supplier.name", filters.get("supplier")),
                SpecificationUtils.containsIgnoreCase("checkNumber", filters.get("checkNumber")),
                SpecificationUtils.booleanEquals("voided", filters.get("voided")),
                SpecificationUtils.dateRange("refundDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        ), pageable);
    }

    public SupplierRefund findSupplierRefund(Long id, String username) {
        SupplierRefund refund = supplierRefundRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Supplier refund not found: " + id));
        companyAccess.assertCompanyAccess(username, refund.getCompany().getId());
        return refund;
    }

    @Transactional
    public SupplierRefund createSupplierRefund(SupplierRefundRequest request, String username) {
        Company company = loadCompany(request.getCompanyId(), username);
        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new IllegalArgumentException("Supplier not found: " + request.getSupplierId()));
        if (!supplier.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Supplier does not belong to company: " + company.getId());
        }
        Instant refundDate = DateRangeUtils.startOfDayUtc(request.getRefundDate());
        accountingSettingsService.assertOnOrAfterStart(company, refundDate, "Refund");

        SupplierRefund refund = new SupplierRefund();
        refund.setCompany(company);
        refund.setSupplier(supplier);
        refund.setRefundDate(refundDate);
        refund.setSheetNumber(request.getSheetNumber());
        refund.setRemarks(request.getRemarks());
        refund.setMode(request.getMode());
        refund.setCheckNumber(request.getCheckNumber());
        boolean intoAccount = request.getMode() != ReceivedPaymentMode.CASH && request.getMode() != ReceivedPaymentMode.OTHERS;
        refund.setBankAccount(resolveAccount(request.getBankAccountId(), intoAccount, company, request.getMode().name()));
        if (request.getMode() == ReceivedPaymentMode.CHECK) {
            if (!StringUtils.hasText(request.getCheckNumber()) || request.getCheckDate() == null || request.getBankId() == null) {
                throw new IllegalArgumentException("A CHECK refund needs checkNumber, checkDate and bankId");
            }
            Bank bank = bankRepository.findById(request.getBankId())
                    .orElseThrow(() -> new IllegalArgumentException("Bank not found: " + request.getBankId()));
            if (!bank.getCompany().getId().equals(company.getId())) {
                throw new IllegalArgumentException("Bank does not belong to company: " + company.getId());
            }
            refund.setBank(bank);
            refund.setCheckDate(DateRangeUtils.startOfDayUtc(request.getCheckDate()));
        }
        Instant now = Instant.now();
        refund.setCreatedAt(now);
        refund.setCreatedBy(username);

        List<OpenItem> credits = new ArrayList<>();
        BigDecimal amount = BigDecimal.ZERO;
        int lineNumber = 1;
        for (RefundLineRequest l : request.getLines()) {
            OpenItem credit = requireCredit(l.getCreditItemId(), company, AccountSide.PAYABLE, supplier.getId());
            SupplierRefundLine line = new SupplierRefundLine();
            line.setSupplierRefund(refund);
            line.setCreditItem(credit);
            line.setAmount(l.getAmount());
            line.setLineNumber(lineNumber++);
            refund.getLines().add(line);
            credits.add(credit);
            amount = amount.add(l.getAmount());
        }
        refund.setAmount(amount);

        refund.setReferenceNumber(transactionReferenceService.next(company.getId(), TransactionType.SUPPLIER_REFUND.name(), "SRF"));
        SupplierRefund saved = supplierRefundRepository.save(refund);
        transactionEventService.recordSystemEvent(company, TransactionType.SUPPLIER_REFUND, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        OpenItem charge = openItemService.open(company, AccountSide.PAYABLE, null, supplier, OpenItemDirection.CHARGE,
                OpenItemSourceType.SUPPLIER_REFUND, saved.getId(), saved.getReferenceNumber(), refundDate, refundDate, amount, true, username);
        saved.setOpenItem(charge);
        applyCredits(credits, charge, saved.getLines().stream().map(SupplierRefundLine::getAmount).toList(), refundDate,
                TransactionType.SUPPLIER_REFUND, saved.getId(), username);
        if (intoAccount) {
            bankEntryService.post(company, saved.getBankAccount(), refundDate,
                    request.getMode() == ReceivedPaymentMode.CHECK ? BankEntryType.DEPOSIT : BankEntryType.TRANSFER_RECEIPT,
                    amount, TransactionType.SUPPLIER_REFUND, saved.getId(), null, saved.getReferenceNumber(), saved.getCheckNumber(),
                    saved.getCheckDate(), "Refund from " + supplier.getName(), username);
        }
        log.info("User '{}' posted supplier refund (id={}) {} of {} from supplier {}", username, saved.getId(), saved.getReferenceNumber(),
                amount.toPlainString(), supplier.getId());
        return saved;
    }

    // Also how a bounced refund check is handled: void the refund (the credits it used reopen).
    @Transactional
    public SupplierRefund voidSupplierRefund(Long id, String username) {
        SupplierRefund refund = findSupplierRefund(id, username);
        if (refund.isVoided()) {
            throw new IllegalArgumentException("Supplier refund already voided: " + id);
        }
        voidRefund(TransactionType.SUPPLIER_REFUND, refund.getId(), refund.getReferenceNumber(), refund.getRefundDate(), refund.getOpenItem(), username);
        Instant now = Instant.now();
        refund.setVoided(true);
        refund.setVoidedAt(now);
        refund.setVoidedBy(username);
        SupplierRefund saved = supplierRefundRepository.save(refund);
        transactionEventService.recordSystemEvent(saved.getCompany(), TransactionType.SUPPLIER_REFUND, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided supplier refund (id={})", username, id);
        return saved;
    }

    // ---- shared ----

    private void voidRefund(TransactionType type, Long id, String referenceNumber, Instant date, OpenItem charge, String username) {
        if (openItemService.appliedByOthers(charge, type, id)) {
            throw new IllegalArgumentException("Cannot void " + referenceNumber + ": other documents have applied to it — void those first");
        }
        bankEntryService.voidBySource(type, id, username);
        openItemService.reverseBySource(type, id, AllocationReversalReason.VOIDED, date, username);
        openItemService.voidItem(charge, username);
    }

    private void applyCredits(List<OpenItem> credits, OpenItem charge, List<BigDecimal> amounts, Instant date, TransactionType type, Long id,
                              String username) {
        List<OpenItem> toLock = new ArrayList<>(credits);
        toLock.add(charge);
        openItemService.lock(toLock);
        for (int i = 0; i < credits.size(); i++) {
            openItemService.allocate(credits.get(i), charge, amounts.get(i), date, type, id, username);
        }
    }

    private OpenItem requireCredit(Long itemId, Company company, AccountSide side, Long partyId) {
        OpenItem credit = openItemService.require(itemId, company);
        Long owner = side == AccountSide.RECEIVABLE ? (credit.getCustomer() != null ? credit.getCustomer().getId() : null)
                : (credit.getSupplier() != null ? credit.getSupplier().getId() : null);
        if (credit.getSide() != side || credit.getDirection() != OpenItemDirection.CREDIT || !partyId.equals(owner)) {
            throw new IllegalArgumentException(credit.getReferenceNumber() + " is not an unapplied credit of this "
                    + (side == AccountSide.RECEIVABLE ? "customer" : "supplier"));
        }
        if (!credit.isCleared()) {
            throw new IllegalArgumentException(credit.getReferenceNumber() + " is paid by a check that hasn't cleared yet — refund it once it clears");
        }
        return credit;
    }

    private BankAccount resolveAccount(Long bankAccountId, boolean required, Company company, String mode) {
        if (!required) {
            if (bankAccountId != null) throw new IllegalArgumentException("bankAccountId doesn't apply to a " + mode + " refund");
            return null;
        }
        if (bankAccountId == null) throw new IllegalArgumentException("A " + mode + " refund needs bankAccountId");
        BankAccount account = bankAccountRepository.findById(bankAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Bank account not found: " + bankAccountId));
        BankEntryService.assertUsable(account, company);
        return account;
    }

    private Company loadCompany(Long companyId, String username) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + companyId));
        companyAccess.assertCompanyAccess(username, company.getId());
        return company;
    }
}
