package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierRepository extends JpaRepository<Supplier, Long>, JpaSpecificationExecutor<Supplier> {
    boolean existsByCodeAndCompanyId(String code, Long companyId);
}