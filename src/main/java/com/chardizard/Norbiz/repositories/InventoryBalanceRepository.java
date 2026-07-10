package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.InventoryBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface InventoryBalanceRepository extends JpaRepository<InventoryBalance, Long>, JpaSpecificationExecutor<InventoryBalance> {
    Optional<InventoryBalance> findByItemIdAndWarehouseId(Long itemId, Long warehouseId);
}