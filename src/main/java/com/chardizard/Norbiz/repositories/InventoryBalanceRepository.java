package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.InventoryBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InventoryBalanceRepository extends JpaRepository<InventoryBalance, Long>, JpaSpecificationExecutor<InventoryBalance> {
    Optional<InventoryBalance> findByItemIdAndWarehouseId(Long itemId, Long warehouseId);

    List<InventoryBalance> findByWarehouseIdAndItemIdIn(Long warehouseId, Collection<Long> itemIds);

    // Row-level locks held until the posting transaction commits, so two concurrent postings
    // against the same item/warehouse serialize and can't both pass the non-negative stock check
    // on the same stale quantity (see InventoryStockService).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM InventoryBalance b WHERE b.item.id = :itemId AND b.warehouse.id = :warehouseId")
    Optional<InventoryBalance> findForUpdate(@Param("itemId") Long itemId, @Param("warehouseId") Long warehouseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM InventoryBalance b WHERE b.warehouse.id = :warehouseId AND b.item.id IN :itemIds ORDER BY b.item.id")
    List<InventoryBalance> findForUpdate(@Param("warehouseId") Long warehouseId, @Param("itemIds") Collection<Long> itemIds);
}