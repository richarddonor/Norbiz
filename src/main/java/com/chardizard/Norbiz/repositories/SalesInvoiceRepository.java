package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.SalesInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SalesInvoiceRepository extends JpaRepository<SalesInvoice, Long>, JpaSpecificationExecutor<SalesInvoice> {

    boolean existsByDeliveryReceiptIdAndVoidedFalse(Long deliveryReceiptId);
}
