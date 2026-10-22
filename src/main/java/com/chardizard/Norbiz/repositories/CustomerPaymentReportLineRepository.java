package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.CustomerPaymentReportLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CustomerPaymentReportLineRepository extends JpaRepository<CustomerPaymentReportLine, Long>, JpaSpecificationExecutor<CustomerPaymentReportLine> {
}
