package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.PurchaseReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PurchaseReceiptRepository extends JpaRepository<PurchaseReceipt, Long>, JpaSpecificationExecutor<PurchaseReceipt> {
}
