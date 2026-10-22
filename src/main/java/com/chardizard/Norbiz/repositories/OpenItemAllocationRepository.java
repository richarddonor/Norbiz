package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.OpenItemAllocation;
import com.chardizard.Norbiz.models.TransactionType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OpenItemAllocationRepository extends JpaRepository<OpenItemAllocation, Long>, JpaSpecificationExecutor<OpenItemAllocation> {

    List<OpenItemAllocation> findBySourceTypeAndSourceIdOrderByIdAsc(TransactionType sourceType, Long sourceId);

    List<OpenItemAllocation> findByCreditItemIdAndReversedFalseOrderByIdAsc(Long creditItemId);

    List<OpenItemAllocation> findByChargeItemIdAndReversedFalseOrderByIdAsc(Long chargeItemId);

    // Allocations touching the item from documents other than the given one (blocks voiding that item's document).
    @Query("select count(a) > 0 from OpenItemAllocation a where a.reversed = false "
            + "and (a.creditItem.id = :itemId or a.chargeItem.id = :itemId) "
            + "and not (a.sourceType = :sourceType and a.sourceId = :sourceId)")
    boolean anyActiveFromOtherSource(@Param("itemId") Long itemId, @Param("sourceType") TransactionType sourceType,
                                     @Param("sourceId") Long sourceId);

    // Every allocation touching any of the items, active or not — for as-of balances (aging, statements, ledgers).
    @Query("select a from OpenItemAllocation a where a.creditItem.id in :itemIds or a.chargeItem.id in :itemIds")
    List<OpenItemAllocation> findTouching(@Param("itemIds") List<Long> itemIds);

    @Query("select a from OpenItemAllocation a where a.company.id = :companyId "
            + "and (a.creditItem.customer.id = :customerId or a.chargeItem.customer.id = :customerId) "
            + "and (a.allocationDate <= :to or (a.reversed = true and a.reversedDate <= :to))")
    List<OpenItemAllocation> findForCustomerUpTo(@Param("companyId") Long companyId, @Param("customerId") Long customerId,
                                                 @Param("to") Instant to);
}
