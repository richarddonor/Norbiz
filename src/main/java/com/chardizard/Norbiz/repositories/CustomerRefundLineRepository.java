package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.CustomerRefundLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CustomerRefundLineRepository extends JpaRepository<CustomerRefundLine, Long>, JpaSpecificationExecutor<CustomerRefundLine> {
}
