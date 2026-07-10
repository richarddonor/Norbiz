package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.TransactionSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TransactionSequenceRepository extends JpaRepository<TransactionSequence, Long> {

    // Row-level lock held for the duration of the caller's transaction, so two
    // concurrent callers for the same (company, transactionType) serialize
    // instead of both reading the same lastNumber.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM TransactionSequence s WHERE s.company.id = :companyId AND s.transactionType = :transactionType")
    Optional<TransactionSequence> findForUpdate(@Param("companyId") Long companyId, @Param("transactionType") String transactionType);
}
