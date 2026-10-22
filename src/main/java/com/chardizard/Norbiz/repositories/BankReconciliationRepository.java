package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.BankReconciliation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BankReconciliationRepository extends JpaRepository<BankReconciliation, Long>, JpaSpecificationExecutor<BankReconciliation> {

    Optional<BankReconciliation> findFirstByBankAccountIdAndVoidedFalseOrderByStatementDateDescIdDesc(Long bankAccountId);
}
