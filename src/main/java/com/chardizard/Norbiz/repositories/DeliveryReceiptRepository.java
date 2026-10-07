package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.DeliveryReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface DeliveryReceiptRepository extends JpaRepository<DeliveryReceipt, Long>, JpaSpecificationExecutor<DeliveryReceipt> {

    // A Stock Transfer is loaded by at most one live Delivery Receipt (voiding it releases the transfer).
    Optional<DeliveryReceipt> findFirstByStockTransferIdAndVoidedFalse(Long stockTransferId);
}
