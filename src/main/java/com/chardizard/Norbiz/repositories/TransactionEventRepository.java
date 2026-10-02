package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.TransactionEvent;
import com.chardizard.Norbiz.models.TransactionEventType;
import com.chardizard.Norbiz.models.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionEventRepository extends JpaRepository<TransactionEvent, Long> {
    Page<TransactionEvent> findByTransactionTypeAndTransactionIdOrderByPerformedAtAscIdAsc(
            TransactionType transactionType, Long transactionId, Pageable pageable);
    List<TransactionEvent> findByTransactionTypeAndTransactionIdAndEventType(
            TransactionType transactionType, Long transactionId, TransactionEventType eventType);
    boolean existsByTransactionTypeAndTransactionIdAndActionDefinitionIdAndPerformedBy(
            TransactionType transactionType, Long transactionId, Long actionDefinitionId, String performedBy);
    boolean existsByActionDefinitionId(Long actionDefinitionId);
}
