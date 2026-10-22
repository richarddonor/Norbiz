package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.SupplierPaymentTender;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierPaymentTenderRepository extends JpaRepository<SupplierPaymentTender, Long>, JpaSpecificationExecutor<SupplierPaymentTender> {
}
