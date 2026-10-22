package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.SupplierRefundLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierRefundLineRepository extends JpaRepository<SupplierRefundLine, Long>, JpaSpecificationExecutor<SupplierRefundLine> {
}
