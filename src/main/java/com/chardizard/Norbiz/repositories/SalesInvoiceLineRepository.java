package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.SalesInvoiceLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SalesInvoiceLineRepository extends JpaRepository<SalesInvoiceLine, Long>, JpaSpecificationExecutor<SalesInvoiceLine> {
}
