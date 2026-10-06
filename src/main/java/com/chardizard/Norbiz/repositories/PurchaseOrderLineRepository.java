package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.PurchaseOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

// Backs the detailed report only — lines are otherwise created/read through their transaction.
public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, Long>, JpaSpecificationExecutor<PurchaseOrderLine> {
}
