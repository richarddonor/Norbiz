package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.CustomerCreditApplicationLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CustomerCreditApplicationLineRepository extends JpaRepository<CustomerCreditApplicationLine, Long>, JpaSpecificationExecutor<CustomerCreditApplicationLine> {
}
