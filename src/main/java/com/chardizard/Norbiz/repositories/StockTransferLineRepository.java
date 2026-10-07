package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.StockTransferLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

// Backs the detailed report only — lines are otherwise created/read through their transaction.
public interface StockTransferLineRepository extends JpaRepository<StockTransferLine, Long>, JpaSpecificationExecutor<StockTransferLine> {
}
