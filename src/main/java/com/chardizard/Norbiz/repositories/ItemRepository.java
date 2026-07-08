package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.Item;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, Long>, JpaSpecificationExecutor<Item> {
    Page<Item> findByCompanyIdIn(List<Long> companyIds, Pageable pageable);
    Optional<Item> findByCompanyIdAndItemCode(Long companyId, String itemCode);
    boolean existsByCompanyIdAndItemCode(Long companyId, String itemCode);
}
