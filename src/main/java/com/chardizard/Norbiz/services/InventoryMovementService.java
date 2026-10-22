package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.InventoryMovement;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.InventoryMovementRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import com.chardizard.Norbiz.util.ListTotals;
import com.chardizard.Norbiz.util.SpecificationUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryMovementService {

    private final InventoryMovementRepository inventoryMovementRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;

    public Page<InventoryMovement> findAllForUser(String username, Long warehouseId, Long itemId,
                                                  Instant dateFrom, Instant dateTo, Pageable pageable) {
        Specification<InventoryMovement> spec = spec(username, warehouseId, itemId, dateFrom, dateTo);
        return spec == null ? Page.empty(pageable) : inventoryMovementRepository.findAll(spec, pageable);
    }

    /**
     * Grand totals of the ledger's quantity columns over every movement {@link #findAllForUser} would list,
     * keyed by the list's column keys. Both are stored columns, so this is one SQL SUM — no row limit,
     * unlike the Java-computed totals of other lists (see {@code ListTotals}).
     */
    public Map<String, BigDecimal> totals(String username, Long warehouseId, Long itemId, Instant dateFrom, Instant dateTo) {
        Specification<InventoryMovement> spec = spec(username, warehouseId, itemId, dateFrom, dateTo);
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        if (spec == null) return totals;

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> query = cb.createQuery(Object[].class);
        Root<InventoryMovement> root = query.from(InventoryMovement.class);
        Predicate where = spec.toPredicate(root, query, cb);
        query.multiselect(cb.sum(root.<BigDecimal>get("quantityDelta")), cb.sum(root.<BigDecimal>get("transitQuantityDelta")));
        if (where != null) query.where(where);
        Object[] sums = entityManager.createQuery(query).getSingleResult();
        ListTotals.add(totals, "quantityDelta", (BigDecimal) sums[0]);
        ListTotals.add(totals, "transitQuantityDelta", (BigDecimal) sums[1]);
        return totals;
    }

    /** The list's filter, or null when the user belongs to no company (nothing to list). */
    private Specification<InventoryMovement> spec(String username, Long warehouseId, Long itemId, Instant dateFrom, Instant dateTo) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<InventoryMovement> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return null;
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<InventoryMovement> warehouseScope = warehouseId == null ? null
                : (root, query, cb) -> cb.equal(root.get("warehouse").get("id"), warehouseId);
        Specification<InventoryMovement> itemScope = itemId == null ? null
                : (root, query, cb) -> cb.equal(root.get("item").get("id"), itemId);

        return SpecificationUtils.allOf(
                companyScope,
                warehouseScope,
                itemScope,
                SpecificationUtils.dateRange("movementDate", dateFrom, dateTo)
        );
    }
}
