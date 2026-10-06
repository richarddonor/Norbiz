package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.OutletDeliveryReturn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OutletDeliveryReturnRepository extends JpaRepository<OutletDeliveryReturn, Long>, JpaSpecificationExecutor<OutletDeliveryReturn> {
}
