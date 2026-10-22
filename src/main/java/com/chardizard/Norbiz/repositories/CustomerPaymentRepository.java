package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.CustomerPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CustomerPaymentRepository extends JpaRepository<CustomerPayment, Long>, JpaSpecificationExecutor<CustomerPayment> {
}
