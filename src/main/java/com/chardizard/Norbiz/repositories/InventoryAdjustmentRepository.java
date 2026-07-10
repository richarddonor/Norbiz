package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.InventoryAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface InventoryAdjustmentRepository extends JpaRepository<InventoryAdjustment, Long>, JpaSpecificationExecutor<InventoryAdjustment> {
}