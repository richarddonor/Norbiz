package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.CustomerMemoLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CustomerMemoLineRepository extends JpaRepository<CustomerMemoLine, Long>, JpaSpecificationExecutor<CustomerMemoLine> {
}
