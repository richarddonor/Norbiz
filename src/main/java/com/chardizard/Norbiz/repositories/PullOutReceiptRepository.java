package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.PullOutReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PullOutReceiptRepository extends JpaRepository<PullOutReceipt, Long>, JpaSpecificationExecutor<PullOutReceipt> {
}
