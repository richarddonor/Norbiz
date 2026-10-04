package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.DeliveryReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DeliveryReceiptRepository extends JpaRepository<DeliveryReceipt, Long>, JpaSpecificationExecutor<DeliveryReceipt> {
}
