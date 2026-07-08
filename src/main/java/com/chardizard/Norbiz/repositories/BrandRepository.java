package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.Brand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface BrandRepository extends JpaRepository<Brand, Long>, JpaSpecificationExecutor<Brand> {
    Page<Brand> findByCompanyIdIn(List<Long> companyIds, Pageable pageable);
    boolean existsByNameAndCompanyId(String name, Long companyId);
}