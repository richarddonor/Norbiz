package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.InventoryMovement;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.InventoryMovementRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import com.chardizard.Norbiz.util.SpecificationUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryMovementService {

    private final InventoryMovementRepository inventoryMovementRepository;
    private final UserRepository userRepository;

    public Page<InventoryMovement> findAllForUser(String username, Long warehouseId, Long itemId,
                                                  Instant dateFrom, Instant dateTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<InventoryMovement> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<InventoryMovement> warehouseScope = warehouseId == null ? null
                : (root, query, cb) -> cb.equal(root.get("warehouse").get("id"), warehouseId);
        Specification<InventoryMovement> itemScope = itemId == null ? null
                : (root, query, cb) -> cb.equal(root.get("item").get("id"), itemId);

        Specification<InventoryMovement> spec = SpecificationUtils.allOf(
                companyScope,
                warehouseScope,
                itemScope,
                SpecificationUtils.dateRange("movementDate", dateFrom, dateTo)
        );

        return inventoryMovementRepository.findAll(spec, pageable);
    }
}