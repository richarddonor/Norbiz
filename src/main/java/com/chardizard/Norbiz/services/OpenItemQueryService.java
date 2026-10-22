package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.OpenItemAllocationRepository;
import com.chardizard.Norbiz.repositories.OpenItemRepository;
import com.chardizard.Norbiz.security.CompanyAccess;
import com.chardizard.Norbiz.util.SpecificationUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read side of the AR/AP subledger: open-item lists and lookups, and a document's allocations. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OpenItemQueryService {

    private final OpenItemRepository openItemRepository;
    private final OpenItemAllocationRepository allocationRepository;
    private final CompanyAccess companyAccess;

    public List<OpenItemAllocation> allocationsBySource(TransactionType sourceType, Long sourceId) {
        return allocationRepository.findBySourceTypeAndSourceIdOrderByIdAsc(sourceType, sourceId);
    }

    /** Allocations touching an item, active or reversed (an invoice's payment history). */
    public List<OpenItemAllocation> allocationsTouching(OpenItem item) {
        return allocationRepository.findTouching(List.of(item.getId()));
    }

    /**
     * Open items of one side, across the caller's companies (companyId narrows to one). overdueAsOf keeps only
     * OPEN charges due before that date.
     */
    public Page<OpenItem> findAllForUser(String username, AccountSide side, Long companyId, Long customerId, Long supplierId,
                                         OpenItemDirection direction, OpenItemStatus status, Instant overdueAsOf,
                                         Map<String, String> filters, Instant dateFrom, Instant dateTo, Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyId != null) {
            companyAccess.assertCompanyAccess(username, companyId);
            companyIds = List.of(companyId);
        }
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        List<Long> scoped = companyIds;
        Specification<OpenItem> companyScope = scoped == null ? null : (root, query, cb) -> root.get("company").get("id").in(scoped);
        Specification<OpenItem> overdue = overdueAsOf == null ? null : (root, query, cb) -> cb.and(
                cb.equal(root.get("direction"), OpenItemDirection.CHARGE),
                cb.equal(root.get("status"), OpenItemStatus.OPEN),
                cb.lessThan(root.get("dueDate"), overdueAsOf));

        return openItemRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                (root, query, cb) -> cb.equal(root.get("side"), side),
                idEquals("customer", customerId),
                idEquals("supplier", supplierId),
                direction == null ? null : (root, query, cb) -> cb.equal(root.get("direction"), direction),
                status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status),
                overdue,
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("customer.name", filters.get("customer")),
                SpecificationUtils.containsIgnoreCase("supplier.name", filters.get("supplier")),
                SpecificationUtils.enumEquals("sourceType", OpenItemSourceType.class, filters.get("sourceType")),
                SpecificationUtils.dateRange("documentDate", dateFrom, dateTo)
        ), pageable);
    }

    /** Form dropdown: one party's items that can still be applied (OPEN), scoped to one company. */
    public Page<OpenItem> lookup(String username, Long companyId, AccountSide side, Long customerId, Long supplierId,
                                 OpenItemDirection direction, String q, Pageable pageable) {
        if (companyId == null) {
            throw new IllegalArgumentException("companyId is required (query parameter or X-Company-Id header)");
        }
        companyAccess.assertCompanyAccess(username, companyId);
        return openItemRepository.findAll(SpecificationUtils.allOf(
                (root, query, cb) -> cb.equal(root.get("company").get("id"), companyId),
                (root, query, cb) -> cb.equal(root.get("side"), side),
                (root, query, cb) -> cb.equal(root.get("status"), OpenItemStatus.OPEN),
                idEquals("customer", customerId),
                idEquals("supplier", supplierId),
                direction == null ? null : (root, query, cb) -> cb.equal(root.get("direction"), direction),
                SpecificationUtils.containsIgnoreCase("referenceNumber", q)
        ), pageable);
    }

    public OpenItem findById(Long id, String username) {
        OpenItem item = openItemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Open item not found: " + id));
        companyAccess.assertCompanyAccess(username, item.getCompany().getId());
        return item;
    }

    private static Specification<OpenItem> idEquals(String association, Long id) {
        if (id == null) return null;
        return (root, query, cb) -> cb.equal(root.get(association).get("id"), id);
    }
}
