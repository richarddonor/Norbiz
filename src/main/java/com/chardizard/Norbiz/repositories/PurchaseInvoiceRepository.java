package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.PurchaseInvoice;
import com.chardizard.Norbiz.models.TransactionOrigin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;

public interface PurchaseInvoiceRepository extends JpaRepository<PurchaseInvoice, Long>, JpaSpecificationExecutor<PurchaseInvoice> {

    List<PurchaseInvoice> findByCompanyIdAndOpenItemIsNotNull(Long companyId);

    List<PurchaseInvoice> findByCompanyIdAndOriginAndVoidedFalseAndInvoiceDateGreaterThanEqual(Long companyId, TransactionOrigin origin,
                                                                                              Instant from);
}
