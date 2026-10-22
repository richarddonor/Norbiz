package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.CustomerCreditApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CustomerCreditApplicationRepository extends JpaRepository<CustomerCreditApplication, Long>, JpaSpecificationExecutor<CustomerCreditApplication> {
}
