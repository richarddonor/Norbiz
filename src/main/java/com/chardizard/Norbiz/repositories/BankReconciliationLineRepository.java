package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.BankReconciliationLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BankReconciliationLineRepository extends JpaRepository<BankReconciliationLine, Long>, JpaSpecificationExecutor<BankReconciliationLine> {
}
