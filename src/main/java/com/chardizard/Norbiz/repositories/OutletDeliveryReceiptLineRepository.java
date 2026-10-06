package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.OutletDeliveryReceiptLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

// Backs the detailed report only — lines are otherwise created/read through their transaction.
public interface OutletDeliveryReceiptLineRepository extends JpaRepository<OutletDeliveryReceiptLine, Long>, JpaSpecificationExecutor<OutletDeliveryReceiptLine> {
}
