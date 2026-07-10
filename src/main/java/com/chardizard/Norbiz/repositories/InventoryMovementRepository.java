package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.InventoryMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long>, JpaSpecificationExecutor<InventoryMovement> {

    // Reconstructs a historical balance as of a given date by summing all ledger
    // entries up to it. Not paginated in SQL — the result set is one row per
    // distinct (item, warehouse) combination that has ever moved, which is bounded
    // enough per company to page in-memory in the service layer.
    @Query("""
            SELECT m.item.id, m.warehouse.id, SUM(m.quantityDelta), SUM(m.transitQuantityDelta)
            FROM InventoryMovement m
            WHERE m.movementDate <= :asOfDate
              AND (:companyIds IS NULL OR m.company.id IN :companyIds)
              AND (:warehouseId IS NULL OR m.warehouse.id = :warehouseId)
              AND (:itemId IS NULL OR m.item.id = :itemId)
            GROUP BY m.item.id, m.warehouse.id
            """)
    List<Object[]> aggregateBalanceAsOf(@Param("asOfDate") Instant asOfDate,
                                         @Param("companyIds") List<Long> companyIds,
                                         @Param("warehouseId") Long warehouseId,
                                         @Param("itemId") Long itemId);
}