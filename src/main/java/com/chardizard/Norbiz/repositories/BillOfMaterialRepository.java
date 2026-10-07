package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.BillOfMaterial;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BillOfMaterialRepository extends JpaRepository<BillOfMaterial, Long>, JpaSpecificationExecutor<BillOfMaterial> {
    boolean existsByCodeAndCompanyId(String code, Long companyId);
}
