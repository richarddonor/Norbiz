package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.OutletReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OutletReceiptRepository extends JpaRepository<OutletReceipt, Long>, JpaSpecificationExecutor<OutletReceipt> {
}
