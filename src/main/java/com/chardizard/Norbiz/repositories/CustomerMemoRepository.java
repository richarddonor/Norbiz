package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.CustomerMemo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CustomerMemoRepository extends JpaRepository<CustomerMemo, Long>, JpaSpecificationExecutor<CustomerMemo> {
}
