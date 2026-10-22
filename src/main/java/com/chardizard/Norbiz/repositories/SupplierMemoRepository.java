package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.SupplierMemo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierMemoRepository extends JpaRepository<SupplierMemo, Long>, JpaSpecificationExecutor<SupplierMemo> {
}
