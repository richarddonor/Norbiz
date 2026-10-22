package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.DepositRequest;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Deposit (docs/ACCOUNTING.md "Deposits"): takes ON_HAND cash and checks received from customers to one of our bank
 * accounts as one deposit slip. Checks become DEPOSITED (cleared later); cash is cleared at once. Posts one DEPOSIT
 * entry to the account's book.
 */
@Service
@RequiredArgsConstructor
public class DepositService {

    private static final Logger log = LoggerFactory.getLogger(DepositService.class);
    private static final TransactionType TYPE = TransactionType.DEPOSIT;
    private static final String REFERENCE_PREFIX = "DEP";

    private final DepositRepository depositRepository;
    private final CustomerPaymentTenderRepository tenderRepository;
    private final CompanyRepository companyRepository;
    private final BankAccountRepository bankAccountRepository;
    private final BankEntryRepository bankEntryRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;
    private final AccountingSettingsService accountingSettingsService;
    private final BankEntryService bankEntryService;
    private final CheckStatusService checkStatusService;
    private final CompanyAccess companyAccess;

    public Page<Deposit> findAllForUser(String username, Long bankAccountId, Map<String, String> filters, Instant dateFrom, Instant dateTo,
                                        Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        Specification<Deposit> companyScope = companyIds == null ? null : (root, query, cb) -> root.get("company").get("id").in(companyIds);
        return depositRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                bankAccountId == null ? null : (root, query, cb) -> cb.equal(root.get("bankAccount").get("id"), bankAccountId),
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.containsIgnoreCase("remarks", filters.get("remarks")),
                SpecificationUtils.anyContainsIgnoreCase(filters.get("checkNumber"), "lines.tender.checkNumber"),
                SpecificationUtils.booleanEquals("voided", filters.get("voided")),
                SpecificationUtils.dateRange("depositDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        ), pageable);
    }

    public Deposit findById(Long id, String username) {
        Deposit deposit = depositRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Deposit not found: " + id));
        companyAccess.assertCompanyAccess(username, deposit.getCompany().getId());
        return deposit;
    }

    /** True once a Bank Reconciliation has cleared the deposit's bank entry. */
    public boolean isReconciled(Deposit deposit) {
        return bankEntryRepository.findBySourceTypeAndSourceIdOrderByIdAsc(TYPE, deposit.getId()).stream()
                .anyMatch(e -> e.getEntryType() == BankEntryType.DEPOSIT && e.isCleared());
    }

    @Transactional
    public Deposit create(DepositRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));
        companyAccess.assertCompanyAccess(username, company.getId());
        BankAccount account = bankAccountRepository.findById(request.getBankAccountId())
                .orElseThrow(() -> new IllegalArgumentException("Bank account not found: " + request.getBankAccountId()));
        BankEntryService.assertUsable(account, company);
        Instant depositDate = DateRangeUtils.startOfDayUtc(request.getDepositDate());
        accountingSettingsService.assertOnOrAfterStart(company, depositDate, "Deposit");

        Instant now = Instant.now();
        Deposit deposit = new Deposit();
        deposit.setCompany(company);
        deposit.setBankAccount(account);
        deposit.setDepositDate(depositDate);
        deposit.setSheetNumber(request.getSheetNumber());
        deposit.setRemarks(request.getRemarks());
        deposit.setCreatedAt(now);
        deposit.setCreatedBy(username);

        BigDecimal total = BigDecimal.ZERO;
        int lineNumber = 1;
        for (Long tenderId : request.getTenderIds().stream().distinct().toList()) {
            CustomerPaymentTender tender = tenderRepository.findById(tenderId)
                    .orElseThrow(() -> new IllegalArgumentException("Payment tender not found: " + tenderId));
            CustomerPayment payment = tender.getCustomerPayment();
            String what = CustomerPaymentService.describe(tender) + " of " + payment.getReferenceNumber();
            if (!payment.getCompany().getId().equals(company.getId())) {
                throw new IllegalArgumentException("Payment tender does not belong to company: " + company.getId());
            }
            if (payment.isVoided()) {
                throw new IllegalArgumentException(what + ": the payment is voided");
            }
            if (tender.getMode() != ReceivedPaymentMode.CASH && tender.getMode() != ReceivedPaymentMode.CHECK) {
                throw new IllegalArgumentException(what + ": only cash and checks are deposited (" + tender.getMode() + " landed in the bank already)");
            }
            if (tender.getStatus() != ReceivedCheckStatus.ON_HAND) {
                throw new IllegalArgumentException(what + " is " + tender.getStatus() + ", not ON_HAND");
            }
            if (payment.getPaymentDate().isAfter(depositDate)) {
                throw new IllegalArgumentException(what + " was received after the deposit date");
            }
            if (tender.getCheckDate() != null && tender.getCheckDate().isAfter(depositDate)) {
                throw new IllegalArgumentException(what + " is post-dated to " + tender.getCheckDate().toString().substring(0, 10)
                        + " — it can't be deposited before then");
            }
            DepositLine line = new DepositLine();
            line.setDeposit(deposit);
            line.setTender(tender);
            line.setAmount(tender.getAmount());
            line.setLineNumber(lineNumber++);
            deposit.getLines().add(line);
            total = total.add(tender.getAmount());
        }
        deposit.setTotalAmount(total);

        deposit.setReferenceNumber(transactionReferenceService.next(company.getId(), TYPE.name(), REFERENCE_PREFIX));
        Deposit saved = depositRepository.save(deposit);
        transactionEventService.recordSystemEvent(company, TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        bankEntryService.post(company, account, depositDate, BankEntryType.DEPOSIT, total, TYPE, saved.getId(), null,
                saved.getReferenceNumber(), null, null, saved.getLines().size() + " item(s)", username);
        for (DepositLine line : saved.getLines()) {
            CustomerPaymentTender tender = line.getTender();
            tender.setDeposit(saved);
            tender.setDepositBankAccount(account);
            tender.setStatus(ReceivedCheckStatus.DEPOSITED);
            tender.setStatusDate(depositDate);
            if (tender.getMode() == ReceivedPaymentMode.CASH) {
                // Cash can't bounce: it is settled as soon as it reaches the bank.
                checkStatusService.markReceivedCleared(tender, depositDate, null, username);
            } else {
                tenderRepository.save(tender);
                checkStatusService.recordReceived(tender, "DEPOSITED (" + saved.getReferenceNumber() + ")", username);
            }
        }
        log.info("User '{}' posted deposit (id={}) {} of {} with {} item(s) to bank account {}", username, saved.getId(),
                saved.getReferenceNumber(), total.toPlainString(), saved.getLines().size(), account.getId());
        return saved;
    }

    /**
     * Cancels a deposit slip posted in error: its cash/checks go back ON_HAND. Refused once reconciled, or once any
     * of its checks has cleared or bounced.
     */
    @Transactional
    public Deposit voidDeposit(Long id, String username) {
        Deposit deposit = findById(id, username);
        if (deposit.isVoided()) {
            throw new IllegalArgumentException("Deposit already voided: " + id);
        }
        for (DepositLine line : deposit.getLines()) {
            CustomerPaymentTender tender = line.getTender();
            // Cash cleared on deposit; a reconciled slip is caught by voidBySource below.
            if (tender.getMode() == ReceivedPaymentMode.CHECK && tender.getStatus() != ReceivedCheckStatus.DEPOSITED) {
                throw new IllegalArgumentException("Cannot void " + deposit.getReferenceNumber() + ": " + CustomerPaymentService.describe(tender)
                        + " of " + tender.getCustomerPayment().getReferenceNumber() + " is " + tender.getStatus());
            }
        }
        bankEntryService.voidBySource(TYPE, deposit.getId(), username);
        for (DepositLine line : deposit.getLines()) {
            CustomerPaymentTender tender = line.getTender();
            tender.setDeposit(null);
            tender.setDepositBankAccount(null);
            tender.setStatus(ReceivedCheckStatus.ON_HAND);
            tender.setStatusDate(tender.getCustomerPayment().getPaymentDate());
            tenderRepository.save(tender);
            if (tender.getMode() == ReceivedPaymentMode.CHECK) {
                checkStatusService.recordReceived(tender, "back to ON_HAND (" + deposit.getReferenceNumber() + " voided)", username);
            }
        }
        Instant now = Instant.now();
        deposit.setVoided(true);
        deposit.setVoidedAt(now);
        deposit.setVoidedBy(username);
        Deposit saved = depositRepository.save(deposit);
        transactionEventService.recordSystemEvent(saved.getCompany(), TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided deposit (id={})", username, id);
        return saved;
    }
}
