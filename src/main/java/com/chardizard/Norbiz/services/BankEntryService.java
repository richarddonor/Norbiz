package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.BankEntryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Writes the bank-side ledger ({@link BankEntry}) for the accounting transactions: deposits, transfers received,
 * checks issued, returned checks. Runs inside the calling transaction service's transaction.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class BankEntryService {

    private static final Logger log = LoggerFactory.getLogger(BankEntryService.class);

    private final BankEntryRepository bankEntryRepository;

    public BankEntry post(Company company, BankAccount account, Instant entryDate, BankEntryType type, BigDecimal amount,
                          TransactionType sourceType, Long sourceId, Long sourceLineId, String referenceNumber,
                          String checkNumber, Instant checkDate, String description, String username) {
        BankEntry entry = new BankEntry();
        entry.setCompany(company);
        entry.setBankAccount(account);
        entry.setEntryDate(entryDate);
        entry.setEntryType(type);
        entry.setAmount(amount);
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);
        entry.setSourceLineId(sourceLineId);
        entry.setReferenceNumber(referenceNumber);
        entry.setCheckNumber(checkNumber);
        entry.setCheckDate(checkDate);
        entry.setDescription(description);
        entry.setCreatedAt(Instant.now());
        entry.setCreatedBy(username);
        BankEntry saved = bankEntryRepository.save(entry);
        log.info("User '{}' posted bank entry {} {} {} to account {} ({} {})", username, saved.getId(), type, amount.toPlainString(),
                account.getId(), sourceType, sourceId);
        return saved;
    }

    /** Voids every entry the document posted. Refused once any of them has been reconciled. */
    public void voidBySource(TransactionType sourceType, Long sourceId, String username) {
        voidAll(bankEntryRepository.findBySourceTypeAndSourceIdOrderByIdAsc(sourceType, sourceId), username);
    }

    /** Voids the entries one tender line posted (an issued check that was cancelled). */
    public void voidBySourceLine(TransactionType sourceType, Long sourceLineId, String username) {
        voidAll(bankEntryRepository.findBySourceTypeAndSourceLineId(sourceType, sourceLineId), username);
    }

    public List<BankEntry> findBySourceLine(TransactionType sourceType, Long sourceLineId) {
        return bankEntryRepository.findBySourceTypeAndSourceLineId(sourceType, sourceLineId).stream().filter(e -> !e.isVoided()).toList();
    }

    private void voidAll(List<BankEntry> entries, String username) {
        for (BankEntry e : entries) {
            if (e.isVoided()) continue;
            if (e.isCleared()) {
                throw new IllegalArgumentException("Bank entry for " + e.getReferenceNumber()
                        + (e.getCheckNumber() != null ? " (check " + e.getCheckNumber() + ")" : "")
                        + " is already reconciled" + (e.getBankReconciliation() != null ? " in " + e.getBankReconciliation().getReferenceNumber() : "")
                        + " — void that reconciliation first");
            }
            e.setVoided(true);
            log.info("User '{}' voided bank entry {} ({} {})", username, e.getId(), e.getSourceType(), e.getSourceId());
        }
    }

    /** The account must belong to the company and be active to receive new postings. */
    public static void assertUsable(BankAccount account, Company company) {
        if (!account.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Bank account does not belong to company: " + company.getId());
        }
        if (!account.isActive()) {
            throw new IllegalArgumentException("Bank account is inactive: " + account.getName());
        }
    }
}
