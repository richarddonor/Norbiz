package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.PurchaseOrderLineRequest;
import com.chardizard.Norbiz.dto.PurchaseOrderRequest;
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
public class PurchaseOrderService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderService.class);
    private static final String TRANSACTION_TYPE = "PURCHASE_ORDER";
    private static final String VOID_SOURCE_TYPE = "PURCHASE_ORDER_VOID";
    private static final String REFERENCE_PREFIX = "PO";

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final CompanyRepository companyRepository;
    private final WarehouseRepository warehouseRepository;
    private final SupplierRepository supplierRepository;
    private final ItemRepository itemRepository;
    private final ItemPriceRepository itemPriceRepository;
    private final UserRepository userRepository;
    private final TransactionReferenceService transactionReferenceService;

    public Page<PurchaseOrder> findAllForUser(String username, Long warehouseId, Long supplierId,
                                               Map<String, String> filters, Instant dateFrom, Instant dateTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<PurchaseOrder> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<PurchaseOrder> warehouseScope = warehouseId == null ? null
                : (root, query, cb) -> cb.equal(root.get("warehouse").get("id"), warehouseId);
        Specification<PurchaseOrder> supplierScope = supplierId == null ? null
                : (root, query, cb) -> cb.equal(root.get("supplier").get("id"), supplierId);
        Specification<PurchaseOrder> spec = SpecificationUtils.allOf(
                companyScope,
                warehouseScope,
                supplierScope,
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.dateRange("orderDate", dateFrom, dateTo)
        );

        return purchaseOrderRepository.findAll(spec, pageable);
    }

    public PurchaseOrder findById(Long id, String username) {
        PurchaseOrder order = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Purchase order not found: " + id));
        assertCompanyAccess(username, order.getCompany().getId());
        return order;
    }

    @Transactional
    public PurchaseOrder create(PurchaseOrderRequest request, String username, boolean canViewCostPrice) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        Warehouse warehouse = warehouseRepository.findById(request.getWarehouseId())
                .orElseThrow(() -> new IllegalArgumentException("Warehouse not found: " + request.getWarehouseId()));
        if (!warehouse.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Warehouse does not belong to company: " + company.getId());
        }

        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new IllegalArgumentException("Supplier not found: " + request.getSupplierId()));
        if (!supplier.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Supplier does not belong to company: " + company.getId());
        }

        Instant orderDate = DateRangeUtils.startOfDayUtc(request.getOrderDate());
        if (orderDate == null) {
            throw new IllegalArgumentException("orderDate is required (yyyy-MM-dd)");
        }

        Instant now = Instant.now();

        PurchaseOrder order = new PurchaseOrder();
        order.setCompany(company);
        order.setWarehouse(warehouse);
        order.setSupplier(supplier);
        order.setOrderDate(orderDate);
        order.setRemarks(request.getRemarks());
        order.setSheetNumber(request.getSheetNumber());
        order.setCreatedAt(now);
        order.setCreatedBy(username);

        for (PurchaseOrderLineRequest lineRequest : request.getLines()) {
            Item item = itemRepository.findById(lineRequest.getItemId())
                    .orElseThrow(() -> new IllegalArgumentException("Item not found: " + lineRequest.getItemId()));
            if (!item.getCompany().getId().equals(company.getId())) {
                throw new IllegalArgumentException("Item does not belong to company: " + company.getId());
            }
            if (!item.getTags().contains(ItemTag.INVENTORY)) {
                throw new IllegalArgumentException("Item is not inventory-tracked: " + item.getItemCode());
            }
            if (lineRequest.getQuantity() == null || lineRequest.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Line quantity must be greater than zero for item: " + item.getItemCode());
            }

            PurchaseOrderLine line = new PurchaseOrderLine();
            line.setPurchaseOrder(order);
            line.setItem(item);
            line.setQuantity(lineRequest.getQuantity());
            line.setCostPrice(resolveCostPrice(lineRequest, item, canViewCostPrice));
            order.getLines().add(line);
        }

        // Generated last, only once validation has fully passed, to avoid burning
        // reference numbers on requests that were always going to be rejected.
        order.setReferenceNumber(transactionReferenceService.next(company.getId(), TRANSACTION_TYPE, REFERENCE_PREFIX));

        PurchaseOrder saved = purchaseOrderRepository.save(order);

        for (PurchaseOrderLine line : saved.getLines()) {
            postTransitMovement(TRANSACTION_TYPE, company, line.getItem(), warehouse, line.getQuantity(), orderDate, saved.getId(),
                    saved.getReferenceNumber(), saved.getSheetNumber(), now, username);
        }

        log.info("User '{}' posted purchase order (id={}) with {} line(s) for warehouse {} / supplier {}",
                username, saved.getId(), saved.getLines().size(), warehouse.getId(), supplier.getId());
        return saved;
    }

    // Cost price is sensitive — mirrors ItemService.applyPrices' defensive handling for VIEW_COST_PRICE.
    private BigDecimal resolveCostPrice(PurchaseOrderLineRequest lineRequest, Item item, boolean canViewCostPrice) {
        if (!canViewCostPrice) {
            if (lineRequest.getCostPrice() != null && lineRequest.getCostPrice().compareTo(BigDecimal.ZERO) != 0) {
                log.warn("Ignored attempt to set cost price to {} on a purchase order line without VIEW_COST_PRICE authority; forcing 0",
                        lineRequest.getCostPrice());
            }
            return BigDecimal.ZERO;
        }
        if (lineRequest.getCostPrice() != null) {
            return lineRequest.getCostPrice();
        }
        // Preload from the item's current cost price when the request omits one.
        return itemPriceRepository.findByItemIdAndPriceType(item.getId(), PriceType.COST_PRICE)
                .map(ItemPrice::getAmount)
                .orElse(BigDecimal.ZERO);
    }

    // Voiding is the only sanctioned way to cancel an immutable transaction (docs/TRANSACTIONS.md "Voiding").
    // Reverses the transit-quantity posting so it doesn't stay live after the order is cancelled.
    @Transactional
    public PurchaseOrder voidPurchaseOrder(Long id, String username) {
        PurchaseOrder order = findById(id, username);

        if (order.isVoided()) {
            throw new IllegalArgumentException("Purchase order already voided: " + id);
        }
        boolean hasLoadedQuantity = order.getLines().stream()
                .anyMatch(l -> l.getQuantityLoaded().compareTo(BigDecimal.ZERO) > 0);
        if (order.isLoaded() || hasLoadedQuantity) {
            throw new IllegalArgumentException("Cannot void a purchase order that has been loaded: " + id);
        }

        Instant now = Instant.now();
        for (PurchaseOrderLine line : order.getLines()) {
            postTransitMovement(VOID_SOURCE_TYPE, order.getCompany(), line.getItem(), order.getWarehouse(),
                    line.getQuantity().negate(), order.getOrderDate(), order.getId(),
                    order.getReferenceNumber(), order.getSheetNumber(), now, username);
        }

        order.setVoided(true);
        order.setVoidedAt(now);
        order.setVoidedBy(username);

        PurchaseOrder saved = purchaseOrderRepository.save(order);
        log.info("User '{}' voided purchase order (id={})", username, id);
        return saved;
    }

    // Posts to the *transit* side of the ledger (goods on order but not yet received) — mirrors
    // InventoryAdjustmentService.postMovement, which posts to the main quantity side instead.
    private void postTransitMovement(String sourceType, Company company, Item item, Warehouse warehouse, BigDecimal transitQuantityDelta,
                                      Instant movementDate, Long orderId, String referenceNumber, String sheetNumber,
                                      Instant now, String username) {
        InventoryMovement movement = new InventoryMovement();
        movement.setCompany(company);
        movement.setItem(item);
        movement.setWarehouse(warehouse);
        movement.setQuantityDelta(BigDecimal.ZERO);
        movement.setTransitQuantityDelta(transitQuantityDelta);
        movement.setMovementDate(movementDate);
        movement.setSourceType(sourceType);
        movement.setSourceId(orderId);
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
        balance.setTransitQuantity(balance.getTransitQuantity().add(transitQuantityDelta));
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
