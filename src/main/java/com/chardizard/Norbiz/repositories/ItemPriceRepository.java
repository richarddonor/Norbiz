package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.ItemPrice;
import com.chardizard.Norbiz.models.PriceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItemPriceRepository extends JpaRepository<ItemPrice, Long> {
    List<ItemPrice> findByItemId(Long itemId);
    Optional<ItemPrice> findByItemIdAndPriceType(Long itemId, PriceType priceType);
}
