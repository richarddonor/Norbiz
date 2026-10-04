package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.OutletReceive;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OutletReceiveRepository extends JpaRepository<OutletReceive, Long>, JpaSpecificationExecutor<OutletReceive> {
}
