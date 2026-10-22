package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.OpenItem;
import com.chardizard.Norbiz.models.OpenItemSourceType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OpenItemRepository extends JpaRepository<OpenItem, Long>, JpaSpecificationExecutor<OpenItem> {

    Optional<OpenItem> findBySourceTypeAndSourceId(OpenItemSourceType sourceType, Long sourceId);

    // Accounting start date may only move while no open item has ever been applied.
    @Query("select count(a) > 0 from OpenItemAllocation a where a.company.id = :companyId")
    boolean anyAllocationForCompany(@Param("companyId") Long companyId);

    @Query("select count(o) > 0 from OpenItem o where o.company.id = :companyId and o.sourceType <> :except")
    boolean anyForCompanyExcept(@Param("companyId") Long companyId, @Param("except") OpenItemSourceType except);
}
