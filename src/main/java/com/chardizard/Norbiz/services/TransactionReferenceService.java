package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.models.TransactionSequence;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.TransactionSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates auto-incrementing transaction reference numbers, scoped per company
 * per transaction type, per CLAUDE.md's "Transactions" rule that every new
 * transaction gets one automatically. Reusable by any future transaction
 * service (Purchase Order, Sales Order, etc.), not just Inventory Adjustment.
 *
 * Known edge case: the very first call for a given (company, transactionType)
 * pair has no row to lock yet, so two simultaneous first-callers could both
 * attempt to insert and one would fail on the unique constraint — acceptable
 * for this phase, same as InventoryBalance's documented concurrency simplification.
 */
@Service
@RequiredArgsConstructor
public class TransactionReferenceService {

    private final TransactionSequenceRepository transactionSequenceRepository;
    private final CompanyRepository companyRepository;

    // Runs in its own transaction (REQUIRES_NEW) so the sequence advances even if
    // the caller's own transaction later rolls back — reference numbers may have
    // gaps, but must never repeat.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next(Long companyId, String transactionType, String prefix) {
        TransactionSequence sequence = transactionSequenceRepository.findForUpdate(companyId, transactionType)
                .orElseGet(() -> {
                    TransactionSequence s = new TransactionSequence();
                    s.setCompany(companyRepository.getReferenceById(companyId));
                    s.setTransactionType(transactionType);
                    s.setLastNumber(0L);
                    return s;
                });

        sequence.setLastNumber(sequence.getLastNumber() + 1);
        transactionSequenceRepository.save(sequence);

        return prefix + "-" + String.format("%06d", sequence.getLastNumber());
    }
}
