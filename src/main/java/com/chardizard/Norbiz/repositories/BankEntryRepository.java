package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.BankEntry;
import com.chardizard.Norbiz.models.TransactionType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BankEntryRepository extends JpaRepository<BankEntry, Long>, JpaSpecificationExecutor<BankEntry> {

    List<BankEntry> findBySourceTypeAndSourceIdOrderByIdAsc(TransactionType sourceType, Long sourceId);

    List<BankEntry> findBySourceTypeAndSourceLineId(TransactionType sourceType, Long sourceLineId);

    List<BankEntry> findByBankReconciliationIdOrderByIdAsc(Long bankReconciliationId);

    // Reconciliation candidates: everything on the book up to the statement date the bank hasn't cleared yet.
    @Query("select e from BankEntry e where e.bankAccount.id = :accountId and e.voided = false and e.cleared = false "
            + "and e.entryDate <= :to order by e.entryDate asc, e.id asc")
    List<BankEntry> findUncleared(@Param("accountId") Long accountId, @Param("to") Instant to);

    @Query("select coalesce(sum(e.amount), 0) from BankEntry e where e.bankAccount.id = :accountId and e.voided = false "
            + "and e.entryDate <= :to")
    BigDecimal sumUpTo(@Param("accountId") Long accountId, @Param("to") Instant to);

    @Query("select coalesce(sum(e.amount), 0) from BankEntry e where e.bankAccount.id = :accountId and e.voided = false "
            + "and e.entryDate < :from")
    BigDecimal sumBefore(@Param("accountId") Long accountId, @Param("from") Instant from);

    @Query("select e from BankEntry e where e.bankAccount.id = :accountId and e.voided = false "
            + "and e.entryDate >= :from and e.entryDate <= :to order by e.entryDate asc, e.id asc")
    List<BankEntry> findBetween(@Param("accountId") Long accountId, @Param("from") Instant from, @Param("to") Instant to);
}
