package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.ItemSku;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface ItemSkuRepository extends JpaRepository<ItemSku, Long>, JpaSpecificationExecutor<ItemSku> {
    Optional<ItemSku> findBySkuCode(String skuCode);
    List<ItemSku> findByItemId(Long itemId);
    Page<ItemSku> findByItemCompanyIdIn(List<Long> companyIds, Pageable pageable);
    boolean existsBySkuCode(String skuCode);
}
