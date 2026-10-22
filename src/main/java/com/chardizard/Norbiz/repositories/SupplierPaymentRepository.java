package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.SupplierPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, Long>, JpaSpecificationExecutor<SupplierPayment> {
}
