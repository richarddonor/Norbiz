package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.SupplierCreditApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierCreditApplicationRepository extends JpaRepository<SupplierCreditApplication, Long>, JpaSpecificationExecutor<SupplierCreditApplication> {
}
