package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.ItemSku;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface ItemSkuRepository extends JpaRepository<ItemSku, Long>, JpaSpecificationExecutor<ItemSku> {
    List<ItemSku> findByItemId(Long itemId);
    Page<ItemSku> findByCompanyIdIn(List<Long> companyIds, Pageable pageable);
    boolean existsByItemIdAndSkuCode(Long itemId, String skuCode);
}
