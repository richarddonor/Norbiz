package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.SupplierPaymentReportLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierPaymentReportLineRepository extends JpaRepository<SupplierPaymentReportLine, Long>, JpaSpecificationExecutor<SupplierPaymentReportLine> {
}
