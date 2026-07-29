package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.PurchaseReceive;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PurchaseReceiveRepository extends JpaRepository<PurchaseReceive, Long>, JpaSpecificationExecutor<PurchaseReceive> {
}
