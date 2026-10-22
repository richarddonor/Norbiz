package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.BankReconciliationAdjustmentRequest;
import com.chardizard.Norbiz.dto.BankReconciliationRequest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bank Reconciliation (docs/ACCOUNTING.md "Bank reconciliation"): matches one bank account's book to a bank statement.
 * The user ticks the book entries the statement shows (deposits, our checks, transfers, returned checks) and adds the
 * bank-only items (charges, interest); previous balance + cleared + adjustments must equal the statement's ending
 * balance. Clearing a deposit clears its still-DEPOSITED checks; clearing one of our check disbursements clears the
 * check. Only an account's latest reconciliation can be voided, which undoes all of that.
 */
@Service
@RequiredArgsConstructor
public class BankReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(BankReconciliationService.class);
    private static final TransactionType TYPE = TransactionType.BANK_RECONCILIATION;
    private static final String REFERENCE_PREFIX = "BRC";

    public record Candidates(BankAccount account, Instant statementDate, BigDecimal previousBalance, BankReconciliation previous,
                             BigDecimal bookBalance, List<BankEntry> entries) {
    }

    private final BankReconciliationRepository reconciliationRepository;
    private final BankAccountRepository bankAccountRepository;
    private final BankEntryRepository bankEntryRepository;
    private final DepositRepository depositRepository;
    private final SupplierPaymentTenderRepository supplierTenderRepository;
    private final CustomerPaymentTenderRepository customerTenderRepository;
    private final CustomerRefundRepository customerRefundRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;
    private final CheckStatusService checkStatusService;
    private final CompanyAccess companyAccess;

    public Page<BankReconciliation> findAllForUser(String username, Long bankAccountId, Map<String, String> filters, Instant dateFrom,
                                                   Instant dateTo, Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        Specification<BankReconciliation> companyScope = companyIds == null ? null
                : (root, query, cb) -> root.get("company").get("id").in(companyIds);
        return reconciliationRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                bankAccountId == null ? null : (root, query, cb) -> cb.equal(root.get("bankAccount").get("id"), bankAccountId),
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.containsIgnoreCase("bankAccount.name", filters.get("bankAccount")),
                SpecificationUtils.booleanEquals("voided", filters.get("voided")),
                SpecificationUtils.dateRange("statementDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        ), pageable);
    }

    public BankReconciliation findById(Long id, String username) {
        BankReconciliation reconciliation = reconciliationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Bank reconciliation not found: " + id));
        companyAccess.assertCompanyAccess(username, reconciliation.getCompany().getId());
        return reconciliation;
    }

    /** What a new reconciliation of the account as of statementDate starts from. */
    @Transactional(readOnly = true)
    public Candidates candidates(Long bankAccountId, String statementDate, String username) {
        BankAccount account = loadAccount(bankAccountId, username);
        Instant date = DateRangeUtils.startOfDayUtc(statementDate);
        Optional<BankReconciliation> previous = reconciliationRepository.findFirstByBankAccountIdAndVoidedFalseOrderByStatementDateDescIdDesc(account.getId());
        return new Candidates(account, date, previous.map(BankReconciliation::getStatementEndingBalance).orElse(account.getOpeningBalance()),
                previous.orElse(null), bookBalance(account, date), bankEntryRepository.findUncleared(account.getId(), date));
    }

    @Transactional
    public BankReconciliation create(BankReconciliationRequest request, String username) {
        BankAccount account = loadAccount(request.getBankAccountId(), username);
        Company company = account.getCompany();
        Instant statementDate = DateRangeUtils.startOfDayUtc(request.getStatementDate());
        Optional<BankReconciliation> previous = reconciliationRepository.findFirstByBankAccountIdAndVoidedFalseOrderByStatementDateDescIdDesc(account.getId());
        if (previous.isPresent() && !statementDate.isAfter(previous.get().getStatementDate())) {
            throw new IllegalArgumentException("statementDate must be after the previous reconciliation's ("
                    + previous.get().getReferenceNumber() + ", " + previous.get().getStatementDate().toString().substring(0, 10) + ")");
        }
        BigDecimal previousBalance = previous.map(BankReconciliation::getStatementEndingBalance).orElse(account.getOpeningBalance());

        List<BankEntry> cleared = new ArrayList<>();
        BigDecimal clearedTotal = BigDecimal.ZERO;
        for (Long entryId : request.getClearedEntryIds() != null ? request.getClearedEntryIds().stream().distinct().toList() : List.<Long>of()) {
            BankEntry entry = bankEntryRepository.findById(entryId)
                    .orElseThrow(() -> new IllegalArgumentException("Bank entry not found: " + entryId));
            if (!entry.getBankAccount().getId().equals(account.getId())) {
                throw new IllegalArgumentException("Bank entry " + entryId + " is not on account " + account.getName());
            }
            if (entry.isVoided() || entry.isCleared()) {
                throw new IllegalArgumentException("Bank entry " + entryId + " (" + entry.getReferenceNumber() + ") is "
                        + (entry.isVoided() ? "voided" : "already reconciled"));
            }
            if (entry.getEntryDate().isAfter(statementDate)) {
                throw new IllegalArgumentException("Bank entry " + entryId + " (" + entry.getReferenceNumber() + ") is dated after the statement");
            }
            cleared.add(entry);
            clearedTotal = clearedTotal.add(entry.getAmount());
        }
        List<BankReconciliationAdjustmentRequest> adjustments = request.getAdjustments() != null ? request.getAdjustments() : List.of();
        BigDecimal adjustmentsTotal = BigDecimal.ZERO;
        for (BankReconciliationAdjustmentRequest a : adjustments) {
            int sign = a.getAmount().signum();
            if (sign == 0 || (a.getType() == BankAdjustmentType.BANK_CHARGE && sign > 0) || (a.getType() == BankAdjustmentType.INTEREST && sign < 0)) {
                throw new IllegalArgumentException("Adjustment '" + a.getDescription() + "': a BANK_CHARGE is negative, INTEREST positive, "
                        + "and no adjustment is zero");
            }
            adjustmentsTotal = adjustmentsTotal.add(a.getAmount());
        }
        BigDecimal computed = previousBalance.add(clearedTotal).add(adjustmentsTotal);
        if (computed.compareTo(request.getStatementEndingBalance()) != 0) {
            throw new IllegalArgumentException("Not balanced: previous balance " + previousBalance.toPlainString() + " + cleared "
                    + clearedTotal.toPlainString() + " + adjustments " + adjustmentsTotal.toPlainString() + " = " + computed.toPlainString()
                    + ", but the statement ends at " + request.getStatementEndingBalance().toPlainString() + " (difference "
                    + request.getStatementEndingBalance().subtract(computed).toPlainString() + ")");
        }

        Instant now = Instant.now();
        BankReconciliation reconciliation = new BankReconciliation();
        reconciliation.setCompany(company);
        reconciliation.setBankAccount(account);
        reconciliation.setStatementDate(statementDate);
        reconciliation.setSheetNumber(request.getSheetNumber());
        reconciliation.setRemarks(request.getRemarks());
        reconciliation.setPreviousBalance(previousBalance);
        reconciliation.setStatementEndingBalance(request.getStatementEndingBalance());
        reconciliation.setClearedTotal(clearedTotal);
        reconciliation.setAdjustmentsTotal(adjustmentsTotal);
        reconciliation.setBookBalance(BigDecimal.ZERO);
        reconciliation.setOutstandingTotal(BigDecimal.ZERO);
        reconciliation.setCreatedAt(now);
        reconciliation.setCreatedBy(username);
        reconciliation.setReferenceNumber(transactionReferenceService.next(company.getId(), TYPE.name(), REFERENCE_PREFIX));
        BankReconciliation saved = reconciliationRepository.save(reconciliation);
        transactionEventService.recordSystemEvent(company, TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        int lineNumber = 1;
        for (BankEntry entry : cleared) {
            entry.setCleared(true);
            entry.setClearedDate(statementDate);
            entry.setBankReconciliation(saved);
            bankEntryRepository.save(entry);
            clearInstruments(entry, saved, statementDate, username);
            saved.getLines().add(line(saved, BankReconciliationLineKind.CLEARED_ENTRY, entry, null, lineNumber++));
        }
        for (BankReconciliationAdjustmentRequest a : adjustments) {
            BankEntry entry = new BankEntry();
            entry.setCompany(company);
            entry.setBankAccount(account);
            entry.setEntryDate(statementDate);
            entry.setEntryType(BankEntryType.valueOf(a.getType().name()));
            entry.setAmount(a.getAmount());
            entry.setSourceType(TYPE);
            entry.setSourceId(saved.getId());
            entry.setReferenceNumber(saved.getReferenceNumber());
            entry.setDescription(a.getDescription());
            entry.setCleared(true);
            entry.setClearedDate(statementDate);
            entry.setBankReconciliation(saved);
            entry.setCreatedAt(now);
            entry.setCreatedBy(username);
            bankEntryRepository.save(entry);
            saved.getLines().add(line(saved, BankReconciliationLineKind.ADJUSTMENT, entry, a.getType(), lineNumber++));
        }
        bankEntryRepository.flush();
        saved.setBookBalance(bookBalance(account, statementDate));
        saved.setOutstandingTotal(bankEntryRepository.findUncleared(account.getId(), statementDate).stream()
                .map(BankEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));

        log.info("User '{}' posted bank reconciliation (id={}) {} for account {} as of {}: {} cleared, {} adjustment(s), ending {}", username,
                saved.getId(), saved.getReferenceNumber(), account.getId(), request.getStatementDate(), cleared.size(), adjustments.size(),
                request.getStatementEndingBalance().toPlainString());
        return reconciliationRepository.save(saved);
    }

    /** Voids the account's latest reconciliation: un-clears its entries, voids its adjustments, restores the instruments it cleared. */
    @Transactional
    public BankReconciliation voidReconciliation(Long id, String username) {
        BankReconciliation reconciliation = findById(id, username);
        if (reconciliation.isVoided()) {
            throw new IllegalArgumentException("Bank reconciliation already voided: " + id);
        }
        BankReconciliation latest = reconciliationRepository
                .findFirstByBankAccountIdAndVoidedFalseOrderByStatementDateDescIdDesc(reconciliation.getBankAccount().getId()).orElseThrow();
        if (!latest.getId().equals(reconciliation.getId())) {
            throw new IllegalArgumentException("Only the account's latest reconciliation can be voided (" + latest.getReferenceNumber() + ")");
        }
        for (BankEntry entry : bankEntryRepository.findByBankReconciliationIdOrderByIdAsc(reconciliation.getId())) {
            entry.setCleared(false);
            entry.setClearedDate(null);
            entry.setBankReconciliation(null);
            if (entry.getSourceType() == TYPE) entry.setVoided(true);
            bankEntryRepository.save(entry);
        }
        for (CustomerPaymentTender tender : customerTenderRepository.findAll(
                (root, query, cb) -> cb.equal(root.get("bankReconciliation").get("id"), reconciliation.getId()))) {
            checkStatusService.unmarkReceivedCleared(tender, username);
        }
        for (SupplierPaymentTender tender : supplierTenderRepository.findAll(
                (root, query, cb) -> cb.equal(root.get("bankReconciliation").get("id"), reconciliation.getId()))) {
            checkStatusService.unmarkIssuedCleared(tender, username);
        }
        for (CustomerRefund refund : customerRefundRepository.findAll(
                (root, query, cb) -> cb.equal(root.get("bankReconciliation").get("id"), reconciliation.getId()))) {
            refund.setStatus(IssuedCheckStatus.ISSUED);
            refund.setStatusDate(refund.getRefundDate());
            refund.setBankReconciliation(null);
            customerRefundRepository.save(refund);
        }
        Instant now = Instant.now();
        reconciliation.setVoided(true);
        reconciliation.setVoidedAt(now);
        reconciliation.setVoidedBy(username);
        BankReconciliation saved = reconciliationRepository.save(reconciliation);
        transactionEventService.recordSystemEvent(saved.getCompany(), TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided bank reconciliation (id={})", username, id);
        return saved;
    }

    /** Opening balance plus every non-voided entry dated on/before the date. */
    public BigDecimal bookBalance(BankAccount account, Instant asOf) {
        return account.getOpeningBalance().add(bankEntryRepository.sumUpTo(account.getId(), asOf));
    }

    private void clearInstruments(BankEntry entry, BankReconciliation reconciliation, Instant date, String username) {
        switch (entry.getEntryType()) {
            case DEPOSIT -> depositRepository.findById(entry.getSourceId()).ifPresent(deposit -> deposit.getLines().stream()
                    .map(DepositLine::getTender)
                    .filter(t -> t.getStatus() == ReceivedCheckStatus.DEPOSITED)
                    .forEach(t -> checkStatusService.markReceivedCleared(t, date, reconciliation, username)));
            case CHECK_DISBURSEMENT -> {
                if (entry.getSourceType() == TransactionType.SUPPLIER_PAYMENT) {
                    supplierTenderRepository.findById(entry.getSourceLineId())
                            .filter(t -> t.getStatus() == IssuedCheckStatus.ISSUED)
                            .ifPresent(t -> checkStatusService.markIssuedCleared(t, date, reconciliation, username));
                } else if (entry.getSourceType() == TransactionType.CUSTOMER_REFUND) {
                    customerRefundRepository.findById(entry.getSourceId())
                            .filter(r -> r.getStatus() == IssuedCheckStatus.ISSUED)
                            .ifPresent(r -> {
                                r.setStatus(IssuedCheckStatus.CLEARED);
                                r.setStatusDate(date);
                                r.setBankReconciliation(reconciliation);
                                customerRefundRepository.save(r);
                            });
                }
            }
            default -> { }
        }
    }

    private static BankReconciliationLine line(BankReconciliation reconciliation, BankReconciliationLineKind kind, BankEntry entry,
                                               BankAdjustmentType adjustmentType, int lineNumber) {
        BankReconciliationLine line = new BankReconciliationLine();
        line.setBankReconciliation(reconciliation);
        line.setKind(kind);
        line.setBankEntry(entry);
        line.setAdjustmentType(adjustmentType);
        line.setEntryDate(entry.getEntryDate());
        line.setReferenceNumber(entry.getReferenceNumber());
        line.setCheckNumber(entry.getCheckNumber());
        line.setDescription(entry.getDescription());
        line.setAmount(entry.getAmount());
        line.setLineNumber(lineNumber);
        return line;
    }

    private BankAccount loadAccount(Long bankAccountId, String username) {
        BankAccount account = bankAccountRepository.findById(bankAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Bank account not found: " + bankAccountId));
        companyAccess.assertCompanyAccess(username, account.getCompany().getId());
        return account;
    }
}
