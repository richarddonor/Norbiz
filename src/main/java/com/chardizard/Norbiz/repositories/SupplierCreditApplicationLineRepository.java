package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.SupplierCreditApplicationLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierCreditApplicationLineRepository extends JpaRepository<SupplierCreditApplicationLine, Long>, JpaSpecificationExecutor<SupplierCreditApplicationLine> {
}
