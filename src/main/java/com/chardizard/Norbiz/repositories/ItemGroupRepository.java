package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.ItemGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ItemGroupRepository extends JpaRepository<ItemGroup, Long>, JpaSpecificationExecutor<ItemGroup> {
    boolean existsByNameAndCompanyId(String name, Long companyId);
}
