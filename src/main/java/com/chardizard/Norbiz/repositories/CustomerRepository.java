package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CustomerRepository extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {
    boolean existsByCodeAndCompanyId(String code, Long companyId);
}