package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long>, JpaSpecificationExecutor<Warehouse> {
    boolean existsByCodeAndCompanyId(String code, Long companyId);
}