package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.ItemSku;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItemSkuRepository extends JpaRepository<ItemSku, Long> {
    Optional<ItemSku> findBySkuCode(String skuCode);
    List<ItemSku> findByItemId(Long itemId);
    boolean existsBySkuCode(String skuCode);
}
