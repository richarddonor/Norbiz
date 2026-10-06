package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.OutletDeliveryReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OutletDeliveryReceiptRepository extends JpaRepository<OutletDeliveryReceipt, Long>, JpaSpecificationExecutor<OutletDeliveryReceipt> {
}
