package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.CustomerPaymentTender;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CustomerPaymentTenderRepository extends JpaRepository<CustomerPaymentTender, Long>, JpaSpecificationExecutor<CustomerPaymentTender> {
}
