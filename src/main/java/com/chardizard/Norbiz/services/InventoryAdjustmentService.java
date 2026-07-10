package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.InventoryAdjustmentLineRequest;
import com.chardizard.Norbiz.dto.InventoryAdjustmentRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import com.chardizard.Norbiz.util.DateRangeUtils;
import com.chardizard.Norbiz.util.SpecificationUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryAdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(InventoryAdjustmentService.class);
    private static final String TRANSACTION_TYPE = "INVENTORY_ADJUSTMENT";
    private static final String REFERENCE_PREFIX = "IA";

    private final InventoryAdjustmentRepository inventoryAdjustmentRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final CompanyRepository companyRepository;
    private final WarehouseRepository warehouseRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final TransactionReferenceService transactionReferenceService;

    public Page<InventoryAdjustment> findAllForUser(String username, Long warehouseId, Map<String, String> filters,
                                                     Instant dateFrom, Instant dateTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<InventoryAdjustment> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<InventoryAdjustment> warehouseScope = warehouseId == null ? null
                : (root, query, cb) -> cb.equal(root.get("warehouse").get("id"), warehouseId);

        Specification<InventoryAdjustment> spec = SpecificationUtils.allOf(
                companyScope,
                warehouseScope,
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.dateRange("adjustmentDate", dateFrom, dateTo)
        );

        return inventoryAdjustmentRepository.findAll(spec, pageable);
    }

    public InventoryAdjustment findById(Long id, String username) {
        InventoryAdjustment adjustment = inventoryAdjustmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Inventory adjustment not found: " + id));
        assertCompanyAccess(username, adjustment.getCompany().getId());
        return adjustment;
    }

    @Transactional
    public InventoryAdjustment create(InventoryAdjustmentRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        Warehouse warehouse = warehouseRepository.findById(request.getWarehouseId())
                .orElseThrow(() -> new IllegalArgumentException("Warehouse not found: " + request.getWarehouseId()));
        if (!warehouse.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Warehouse does not belong to company: " + company.getId());
        }

        Instant adjustmentDate = DateRangeUtils.startOfDayUtc(request.getAdjustmentDate());
        if (adjustmentDate == null) {
            throw new IllegalArgumentException("adjustmentDate is required (yyyy-MM-dd)");
        }

        Instant now = Instant.now();

        InventoryAdjustment adjustment = new InventoryAdjustment();
        adjustment.setCompany(company);
        adjustment.setWarehouse(warehouse);
        adjustment.setAdjustmentDate(adjustmentDate);
        adjustment.setReason(request.getReason());
        adjustment.setSheetNumber(request.getSheetNumber());
        adjustment.setCreatedAt(now);
        adjustment.setCreatedBy(username);

        for (InventoryAdjustmentLineRequest lineRequest : request.getLines()) {
            Item item = itemRepository.findById(lineRequest.getItemId())
                    .orElseThrow(() -> new IllegalArgumentException("Item not found: " + lineRequest.getItemId()));
            if (!item.getCompany().getId().equals(company.getId())) {
                throw new IllegalArgumentException("Item does not belong to company: " + company.getId());
            }
            if (!item.getTags().contains(ItemTag.INVENTORY)) {
                throw new IllegalArgumentException("Item is not inventory-tracked: " + item.getItemCode());
            }
            if (lineRequest.getQuantity() == null || lineRequest.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
                throw new IllegalArgumentException("Line quantity must be non-zero for item: " + item.getItemCode());
            }

            InventoryAdjustmentLine line = new InventoryAdjustmentLine();
            line.setAdjustment(adjustment);
            line.setItem(item);
            line.setQuantity(lineRequest.getQuantity());
            adjustment.getLines().add(line);
        }

        // Generated last, only once validation has fully passed, to avoid burning
        // reference numbers on requests that were always going to be rejected.
        adjustment.setReferenceNumber(transactionReferenceService.next(company.getId(), TRANSACTION_TYPE, REFERENCE_PREFIX));

        InventoryAdjustment saved = inventoryAdjustmentRepository.save(adjustment);

        for (InventoryAdjustmentLine line : saved.getLines()) {
            postMovement(company, line.getItem(), warehouse, line.getQuantity(), adjustmentDate, saved.getId(),
                    saved.getReferenceNumber(), saved.getSheetNumber(), now, username);
        }

        log.info("User '{}' posted inventory adjustment (id={}) with {} line(s) for warehouse {}",
                username, saved.getId(), saved.getLines().size(), warehouse.getId());
        return saved;
    }

    private void postMovement(Company company, Item item, Warehouse warehouse, BigDecimal quantityDelta, Instant movementDate,
                               Long adjustmentId, String referenceNumber, String sheetNumber, Instant now, String username) {
        InventoryMovement movement = new InventoryMovement();
        movement.setCompany(company);
        movement.setItem(item);
        movement.setWarehouse(warehouse);
        movement.setQuantityDelta(quantityDelta);
        movement.setTransitQuantityDelta(BigDecimal.ZERO);
        movement.setMovementDate(movementDate);
        movement.setSourceType(TRANSACTION_TYPE);
        movement.setSourceId(adjustmentId);
        movement.setReferenceNumber(referenceNumber);
        movement.setSheetNumber(sheetNumber);
        movement.setCreatedAt(now);
        movement.setCreatedBy(username);
        inventoryMovementRepository.save(movement);

        InventoryBalance balance = inventoryBalanceRepository.findByItemIdAndWarehouseId(item.getId(), warehouse.getId())
                .orElseGet(() -> {
                    InventoryBalance b = new InventoryBalance();
                    b.setItem(item);
                    b.setWarehouse(warehouse);
                    b.setQuantity(BigDecimal.ZERO);
                    b.setTransitQuantity(BigDecimal.ZERO);
                    return b;
                });
        balance.setQuantity(balance.getQuantity().add(quantityDelta));
        balance.setUpdatedAt(now);
        inventoryBalanceRepository.save(balance);
    }

    private void assertCompanyAccess(String username, Long companyId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        if (isSuperAdmin) return;

        boolean hasAccess = user.getCompanies().stream()
                .anyMatch(c -> c.getId().equals(companyId));

        if (!hasAccess) {
            log.warn("User '{}' denied access to company {}", username, companyId);
            throw new SecurityException("Access denied to company: " + companyId);
        }
    }
}