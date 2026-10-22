package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.SupplierMemoLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierMemoLineRepository extends JpaRepository<SupplierMemoLine, Long>, JpaSpecificationExecutor<SupplierMemoLine> {
}
