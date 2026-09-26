package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.PurchaseInvoiceFeeRequest;
import com.chardizard.Norbiz.dto.PurchaseInvoiceLineRequest;
import com.chardizard.Norbiz.dto.PurchaseInvoiceRequest;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PurchaseInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseInvoiceService.class);
    private static final String TRANSACTION_TYPE = "PURCHASE_INVOICE";
    private static final String VOID_SOURCE_TYPE = "PURCHASE_INVOICE_VOID";
    private static final String REFERENCE_PREFIX = "PINV";

    private final PurchaseInvoiceRepository purchaseInvoiceRepository;
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

    public Page<PurchaseInvoice> findAllForUser(String username, Long warehouseId, Long supplierId, PaymentStatus paymentStatus,
                                                 Map<String, String> filters, Instant dateFrom, Instant dateTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<PurchaseInvoice> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<PurchaseInvoice> warehouseScope = warehouseId == null ? null
                : (root, query, cb) -> cb.equal(root.get("warehouse").get("id"), warehouseId);
        Specification<PurchaseInvoice> supplierScope = supplierId == null ? null
                : (root, query, cb) -> cb.equal(root.get("supplier").get("id"), supplierId);
        Specification<PurchaseInvoice> paymentStatusScope = paymentStatus == null ? null
                : (root, query, cb) -> cb.equal(root.get("paymentStatus"), paymentStatus);

        Specification<PurchaseInvoice> spec = SpecificationUtils.allOf(
                companyScope,
                warehouseScope,
                supplierScope,
                paymentStatusScope,
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.dateRange("invoiceDate", dateFrom, dateTo)
        );

        return purchaseInvoiceRepository.findAll(spec, pageable);
    }

    public PurchaseInvoice findById(Long id, String username) {
        PurchaseInvoice invoice = purchaseInvoiceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Purchase invoice not found: " + id));
        assertCompanyAccess(username, invoice.getCompany().getId());
        return invoice;
    }

    @Transactional
    public PurchaseInvoice create(PurchaseInvoiceRequest request, String username, boolean canViewCostPrice) {
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

        Instant invoiceDate = DateRangeUtils.startOfDayUtc(request.getInvoiceDate());
        if (invoiceDate == null) {
            throw new IllegalArgumentException("invoiceDate is required (yyyy-MM-dd)");
        }

        Instant now = Instant.now();

        PurchaseInvoice invoice = new PurchaseInvoice();
        invoice.setCompany(company);
        invoice.setWarehouse(warehouse);
        invoice.setSupplier(supplier);
        invoice.setInvoiceDate(invoiceDate);
        invoice.setRemarks(request.getRemarks());
        invoice.setSheetNumber(request.getSheetNumber());
        invoice.setDiscountPercentage(request.getDiscountPercentage() != null ? request.getDiscountPercentage() : BigDecimal.ZERO);
        invoice.setPaymentStatus(PaymentStatus.UNPAID);
        invoice.setCreatedAt(now);
        invoice.setCreatedBy(username);

        PurchaseOrder purchaseOrder = null;
        if (request.getPurchaseOrderId() != null) {
            purchaseOrder = loadAndValidatePurchaseOrder(request.getPurchaseOrderId(), company, warehouse, supplier);
            invoice.setPurchaseOrder(purchaseOrder);

            Map<Long, BigDecimal> discountByItemId = new HashMap<>();
            if (request.getLines() != null) {
                for (PurchaseInvoiceLineRequest lineRequest : request.getLines()) {
                    if (lineRequest.getItemId() != null && lineRequest.getDiscountPercentage() != null) {
                        discountByItemId.put(lineRequest.getItemId(), lineRequest.getDiscountPercentage());
                    }
                }
            }

            for (PurchaseOrderLine poLine : purchaseOrder.getLines()) {
                PurchaseInvoiceLine line = new PurchaseInvoiceLine();
                line.setPurchaseInvoice(invoice);
                line.setItem(poLine.getItem());
                line.setPurchaseOrderLine(poLine);
                line.setQuantity(poLine.getQuantity());
                line.setCostPrice(poLine.getCostPrice());
                line.setDiscountPercentage(discountByItemId.getOrDefault(poLine.getItem().getId(), BigDecimal.ZERO));
                invoice.getLines().add(line);

                poLine.setQuantityLoaded(poLine.getQuantity());
            }
            purchaseOrder.setLoaded(true);
            // No inventory movement posted here — the PO already posted transit quantity at its own creation.
        } else {
            if (request.getLines() == null || request.getLines().isEmpty()) {
                throw new IllegalArgumentException("lines is required when purchaseOrderId is not supplied (Direct mode)");
            }

            for (PurchaseInvoiceLineRequest lineRequest : request.getLines()) {
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

                PurchaseInvoiceLine line = new PurchaseInvoiceLine();
                line.setPurchaseInvoice(invoice);
                line.setItem(item);
                line.setQuantity(lineRequest.getQuantity());
                line.setCostPrice(resolveCostPrice(lineRequest, item, canViewCostPrice));
                line.setDiscountPercentage(lineRequest.getDiscountPercentage() != null ? lineRequest.getDiscountPercentage() : BigDecimal.ZERO);
                invoice.getLines().add(line);
            }
        }

        if (request.getFees() != null) {
            for (PurchaseInvoiceFeeRequest feeRequest : request.getFees()) {
                PurchaseInvoiceFee fee = new PurchaseInvoiceFee();
                fee.setPurchaseInvoice(invoice);
                fee.setDescription(feeRequest.getDescription());
                fee.setAmount(feeRequest.getAmount());
                invoice.getFees().add(fee);
            }
        }

        // Generated last, only once validation has fully passed, to avoid burning
        // reference numbers on requests that were always going to be rejected.
        invoice.setReferenceNumber(transactionReferenceService.next(company.getId(), TRANSACTION_TYPE, REFERENCE_PREFIX));

        PurchaseInvoice saved = purchaseInvoiceRepository.save(invoice);

        if (purchaseOrder == null) {
            // Direct mode: post a transit movement per line, same as PurchaseOrderService.create.
            for (PurchaseInvoiceLine line : saved.getLines()) {
                postTransitMovement(TRANSACTION_TYPE, company, line.getItem(), warehouse, line.getQuantity(), invoiceDate, saved.getId(),
                        saved.getReferenceNumber(), saved.getSheetNumber(), now, username);
            }
        } else {
            purchaseOrderRepository.save(purchaseOrder);
        }

        log.info("User '{}' posted purchase invoice (id={}) with {} line(s) for warehouse {} / supplier {}{}",
                username, saved.getId(), saved.getLines().size(), warehouse.getId(), supplier.getId(),
                purchaseOrder != null ? " (loaded from PO " + purchaseOrder.getReferenceNumber() + ")" : " (Direct)");
        return saved;
    }

    private PurchaseOrder loadAndValidatePurchaseOrder(Long purchaseOrderId, Company company, Warehouse warehouse, Supplier supplier) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(purchaseOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase order not found: " + purchaseOrderId));
        if (!purchaseOrder.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Purchase order does not belong to company: " + company.getId());
        }
        if (purchaseOrder.isVoided()) {
            throw new IllegalArgumentException("Cannot invoice a voided purchase order: " + purchaseOrderId);
        }
        if (purchaseOrder.isLoaded()) {
            throw new IllegalArgumentException("Purchase order already invoiced: " + purchaseOrderId);
        }
        // A Purchase Receive may have already (partially) consumed this PO's lines — invoicing here
        // copies quantity verbatim and would silently overwrite that tracking. See docs/TRANSACTIONS.md
        // "Purchase Receive": a PO can be consumed by an invoice OR receives, not both.
        boolean hasAnyReceived = purchaseOrder.getLines().stream()
                .anyMatch(l -> l.getQuantityLoaded().compareTo(BigDecimal.ZERO) > 0);
        if (hasAnyReceived) {
            throw new IllegalArgumentException("Cannot invoice a purchase order that has already been (partially) received: " + purchaseOrderId);
        }
        if (!purchaseOrder.getWarehouse().getId().equals(warehouse.getId())) {
            throw new IllegalArgumentException("warehouseId does not match the purchase order's warehouse");
        }
        if (!purchaseOrder.getSupplier().getId().equals(supplier.getId())) {
            throw new IllegalArgumentException("supplierId does not match the purchase order's supplier");
        }
        return purchaseOrder;
    }

    // Cost price is sensitive — mirrors PurchaseOrderService.resolveCostPrice.
    private BigDecimal resolveCostPrice(PurchaseInvoiceLineRequest lineRequest, Item item, boolean canViewCostPrice) {
        if (!canViewCostPrice) {
            if (lineRequest.getCostPrice() != null && lineRequest.getCostPrice().compareTo(BigDecimal.ZERO) != 0) {
                log.warn("Ignored attempt to set cost price to {} on a purchase invoice line without VIEW_COST_PRICE authority; forcing 0",
                        lineRequest.getCostPrice());
            }
            return BigDecimal.ZERO;
        }
        if (lineRequest.getCostPrice() != null) {
            return lineRequest.getCostPrice();
        }
        return itemPriceRepository.findByItemIdAndPriceType(item.getId(), PriceType.COST_PRICE)
                .map(ItemPrice::getAmount)
                .orElse(BigDecimal.ZERO);
    }

    // Voiding is the only sanctioned way to cancel an immutable transaction (docs/TRANSACTIONS.md "Voiding").
    @Transactional
    public PurchaseInvoice voidPurchaseInvoice(Long id, String username) {
        PurchaseInvoice invoice = findById(id, username);

        if (invoice.isVoided()) {
            throw new IllegalArgumentException("Purchase invoice already voided: " + id);
        }
        boolean hasLoadedQuantity = invoice.getLines().stream()
                .anyMatch(l -> l.getQuantityLoaded().compareTo(BigDecimal.ZERO) > 0);
        if (invoice.isLoaded() || hasLoadedQuantity) {
            throw new IllegalArgumentException("Cannot void a purchase invoice that has been loaded: " + id);
        }

        Instant now = Instant.now();

        if (invoice.getPurchaseOrder() == null) {
            // Direct mode: reverse the transit movement this invoice posted at creation.
            for (PurchaseInvoiceLine line : invoice.getLines()) {
                postTransitMovement(VOID_SOURCE_TYPE, invoice.getCompany(), line.getItem(), invoice.getWarehouse(),
                        line.getQuantity().negate(), invoice.getInvoiceDate(), invoice.getId(),
                        invoice.getReferenceNumber(), invoice.getSheetNumber(), now, username);
            }
        } else {
            // PO-based mode: unload the originating PO so it becomes voidable/invoiceable again.
            PurchaseOrder purchaseOrder = invoice.getPurchaseOrder();
            for (PurchaseOrderLine poLine : purchaseOrder.getLines()) {
                poLine.setQuantityLoaded(BigDecimal.ZERO);
            }
            purchaseOrder.setLoaded(false);
            purchaseOrderRepository.save(purchaseOrder);
        }

        invoice.setVoided(true);
        invoice.setVoidedAt(now);
        invoice.setVoidedBy(username);

        PurchaseInvoice saved = purchaseInvoiceRepository.save(invoice);
        log.info("User '{}' voided purchase invoice (id={})", username, id);
        return saved;
    }

    // Posts to the *transit* side of the ledger — mirrors PurchaseOrderService.postTransitMovement.
    private void postTransitMovement(String sourceType, Company company, Item item, Warehouse warehouse, BigDecimal transitQuantityDelta,
                                      Instant movementDate, Long invoiceId, String referenceNumber, String sheetNumber,
                                      Instant now, String username) {
        InventoryMovement movement = new InventoryMovement();
        movement.setCompany(company);
        movement.setItem(item);
        movement.setWarehouse(warehouse);
        movement.setQuantityDelta(BigDecimal.ZERO);
        movement.setTransitQuantityDelta(transitQuantityDelta);
        movement.setMovementDate(movementDate);
        movement.setSourceType(sourceType);
        movement.setSourceId(invoiceId);
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
