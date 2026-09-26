package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.PurchaseReceiveLineRequest;
import com.chardizard.Norbiz.dto.PurchaseReceiveRequest;
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
public class PurchaseReceiveService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseReceiveService.class);
    private static final String TRANSACTION_TYPE = "PURCHASE_RECEIVE";
    private static final String VOID_SOURCE_TYPE = "PURCHASE_RECEIVE_VOID";
    private static final String REFERENCE_PREFIX = "PR";

    private final PurchaseReceiveRepository purchaseReceiveRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseInvoiceRepository purchaseInvoiceRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final CompanyRepository companyRepository;
    private final WarehouseRepository warehouseRepository;
    private final SupplierRepository supplierRepository;
    private final UserRepository userRepository;
    private final TransactionReferenceService transactionReferenceService;

    public Page<PurchaseReceive> findAllForUser(String username, Long warehouseId, Long supplierId,
                                                 Map<String, String> filters, Instant dateFrom, Instant dateTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<PurchaseReceive> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<PurchaseReceive> warehouseScope = warehouseId == null ? null
                : (root, query, cb) -> cb.equal(root.get("warehouse").get("id"), warehouseId);
        Specification<PurchaseReceive> supplierScope = supplierId == null ? null
                : (root, query, cb) -> cb.equal(root.get("supplier").get("id"), supplierId);

        Specification<PurchaseReceive> spec = SpecificationUtils.allOf(
                companyScope,
                warehouseScope,
                supplierScope,
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.dateRange("receiptDate", dateFrom, dateTo)
        );

        return purchaseReceiveRepository.findAll(spec, pageable);
    }

    public PurchaseReceive findById(Long id, String username) {
        PurchaseReceive receive = purchaseReceiveRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Purchase receive not found: " + id));
        assertCompanyAccess(username, receive.getCompany().getId());
        return receive;
    }

    @Transactional
    public PurchaseReceive create(PurchaseReceiveRequest request, String username) {
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

        boolean hasPoSource = request.getPurchaseOrderId() != null;
        boolean hasInvoiceSource = request.getPurchaseInvoiceId() != null;
        if (hasPoSource == hasInvoiceSource) {
            throw new IllegalArgumentException("Exactly one of purchaseOrderId or purchaseInvoiceId is required");
        }

        Instant receiptDate = DateRangeUtils.startOfDayUtc(request.getReceiptDate());
        if (receiptDate == null) {
            throw new IllegalArgumentException("receiptDate is required (yyyy-MM-dd)");
        }

        Instant now = Instant.now();

        PurchaseReceive receive = new PurchaseReceive();
        receive.setCompany(company);
        receive.setWarehouse(warehouse);
        receive.setSupplier(supplier);
        receive.setReceiptDate(receiptDate);
        receive.setRemarks(request.getRemarks());
        receive.setSheetNumber(request.getSheetNumber());
        receive.setCreatedAt(now);
        receive.setCreatedBy(username);

        PurchaseOrder purchaseOrder = null;
        PurchaseInvoice purchaseInvoice = null;

        if (hasPoSource) {
            purchaseOrder = loadAndValidatePurchaseOrder(request.getPurchaseOrderId(), company, warehouse, supplier);
            receive.setPurchaseOrder(purchaseOrder);

            // A PO may legitimately carry more than one line for the same item (PurchaseOrderService
            // doesn't reject it), so lookup must be one-to-many, not toMap — otherwise this throws
            // IllegalStateException: Duplicate key. Requested quantity is allocated across that item's
            // lines oldest-first (by id), splitting into multiple PurchaseReceiveLine rows when a single
            // request line's quantity spans more than one source line.
            Map<Long, List<PurchaseOrderLine>> linesByItemId = purchaseOrder.getLines().stream()
                    .sorted(java.util.Comparator.comparing(PurchaseOrderLine::getId))
                    .collect(Collectors.groupingBy(l -> l.getItem().getId()));

            for (PurchaseReceiveLineRequest lineRequest : request.getLines()) {
                List<PurchaseOrderLine> sourceLines = linesByItemId.get(lineRequest.getItemId());
                if (sourceLines == null) {
                    throw new IllegalArgumentException("Item " + lineRequest.getItemId() + " is not on purchase order: " + request.getPurchaseOrderId());
                }
                BigDecimal totalOutstanding = sourceLines.stream()
                        .map(l -> l.getQuantity().subtract(l.getQuantityLoaded()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (lineRequest.getQuantity().compareTo(totalOutstanding) > 0) {
                    throw new IllegalArgumentException("Quantity to receive (" + lineRequest.getQuantity()
                            + ") exceeds outstanding (" + totalOutstanding + ") for item: " + sourceLines.getFirst().getItem().getItemCode());
                }

                BigDecimal remaining = lineRequest.getQuantity();
                for (PurchaseOrderLine sourceLine : sourceLines) {
                    if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                    BigDecimal outstanding = sourceLine.getQuantity().subtract(sourceLine.getQuantityLoaded());
                    if (outstanding.compareTo(BigDecimal.ZERO) <= 0) continue;
                    BigDecimal allocated = outstanding.min(remaining);

                    PurchaseReceiveLine line = new PurchaseReceiveLine();
                    line.setPurchaseReceive(receive);
                    line.setItem(sourceLine.getItem());
                    line.setPurchaseOrderLine(sourceLine);
                    line.setQuantity(allocated);
                    receive.getLines().add(line);

                    sourceLine.setQuantityLoaded(sourceLine.getQuantityLoaded().add(allocated));
                    remaining = remaining.subtract(allocated);
                }
            }

            purchaseOrder.setLoaded(isFullyLoaded(purchaseOrder.getLines(), PurchaseOrderLine::getQuantity, PurchaseOrderLine::getQuantityLoaded));
        } else {
            purchaseInvoice = loadAndValidatePurchaseInvoice(request.getPurchaseInvoiceId(), company, warehouse, supplier);
            receive.setPurchaseInvoice(purchaseInvoice);

            // Same one-to-many lookup as the PO branch above — a Direct invoice can likewise carry
            // more than one line for the same item.
            Map<Long, List<PurchaseInvoiceLine>> linesByItemId = purchaseInvoice.getLines().stream()
                    .sorted(java.util.Comparator.comparing(PurchaseInvoiceLine::getId))
                    .collect(Collectors.groupingBy(l -> l.getItem().getId()));

            for (PurchaseReceiveLineRequest lineRequest : request.getLines()) {
                List<PurchaseInvoiceLine> sourceLines = linesByItemId.get(lineRequest.getItemId());
                if (sourceLines == null) {
                    throw new IllegalArgumentException("Item " + lineRequest.getItemId() + " is not on purchase invoice: " + request.getPurchaseInvoiceId());
                }
                BigDecimal totalOutstanding = sourceLines.stream()
                        .map(l -> l.getQuantity().subtract(l.getQuantityLoaded()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (lineRequest.getQuantity().compareTo(totalOutstanding) > 0) {
                    throw new IllegalArgumentException("Quantity to receive (" + lineRequest.getQuantity()
                            + ") exceeds outstanding (" + totalOutstanding + ") for item: " + sourceLines.getFirst().getItem().getItemCode());
                }

                BigDecimal remaining = lineRequest.getQuantity();
                for (PurchaseInvoiceLine sourceLine : sourceLines) {
                    if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                    BigDecimal outstanding = sourceLine.getQuantity().subtract(sourceLine.getQuantityLoaded());
                    if (outstanding.compareTo(BigDecimal.ZERO) <= 0) continue;
                    BigDecimal allocated = outstanding.min(remaining);

                    PurchaseReceiveLine line = new PurchaseReceiveLine();
                    line.setPurchaseReceive(receive);
                    line.setItem(sourceLine.getItem());
                    line.setPurchaseInvoiceLine(sourceLine);
                    line.setQuantity(allocated);
                    receive.getLines().add(line);

                    sourceLine.setQuantityLoaded(sourceLine.getQuantityLoaded().add(allocated));
                    remaining = remaining.subtract(allocated);
                }
            }

            purchaseInvoice.setLoaded(isFullyLoaded(purchaseInvoice.getLines(), PurchaseInvoiceLine::getQuantity, PurchaseInvoiceLine::getQuantityLoaded));
        }

        // Generated last, only once validation has fully passed, to avoid burning
        // reference numbers on requests that were always going to be rejected.
        receive.setReferenceNumber(transactionReferenceService.next(company.getId(), TRANSACTION_TYPE, REFERENCE_PREFIX));

        PurchaseReceive saved = purchaseReceiveRepository.save(receive);

        for (PurchaseReceiveLine line : saved.getLines()) {
            postMovement(TRANSACTION_TYPE, company, line.getItem(), warehouse, line.getQuantity(), receiptDate, saved.getId(),
                    saved.getReferenceNumber(), saved.getSheetNumber(), now, username);
        }

        if (purchaseOrder != null) {
            purchaseOrderRepository.save(purchaseOrder);
        } else {
            purchaseInvoiceRepository.save(purchaseInvoice);
        }

        log.info("User '{}' posted purchase receive (id={}) with {} line(s) for warehouse {} / supplier {}{}",
                username, saved.getId(), saved.getLines().size(), warehouse.getId(), supplier.getId(),
                purchaseOrder != null ? " (against PO " + purchaseOrder.getReferenceNumber() + ")"
                        : " (against Direct invoice " + purchaseInvoice.getReferenceNumber() + ")");
        return saved;
    }

    private PurchaseOrder loadAndValidatePurchaseOrder(Long purchaseOrderId, Company company, Warehouse warehouse, Supplier supplier) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(purchaseOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase order not found: " + purchaseOrderId));
        if (!purchaseOrder.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Purchase order does not belong to company: " + company.getId());
        }
        if (purchaseOrder.isVoided()) {
            throw new IllegalArgumentException("Cannot receive against a voided purchase order: " + purchaseOrderId);
        }
        // loaded here means fully consumed downstream, whether by a PO-based invoice or a prior
        // full receive — see docs/TRANSACTIONS.md "Purchase Receive" for why these share one flag.
        if (purchaseOrder.isLoaded()) {
            throw new IllegalArgumentException("Purchase order already fully processed (invoiced or received): " + purchaseOrderId);
        }
        if (!purchaseOrder.getWarehouse().getId().equals(warehouse.getId())) {
            throw new IllegalArgumentException("warehouseId does not match the purchase order's warehouse");
        }
        if (!purchaseOrder.getSupplier().getId().equals(supplier.getId())) {
            throw new IllegalArgumentException("supplierId does not match the purchase order's supplier");
        }
        return purchaseOrder;
    }

    private PurchaseInvoice loadAndValidatePurchaseInvoice(Long purchaseInvoiceId, Company company, Warehouse warehouse, Supplier supplier) {
        PurchaseInvoice purchaseInvoice = purchaseInvoiceRepository.findById(purchaseInvoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase invoice not found: " + purchaseInvoiceId));
        if (!purchaseInvoice.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Purchase invoice does not belong to company: " + company.getId());
        }
        if (purchaseInvoice.getPurchaseOrder() != null) {
            throw new IllegalArgumentException("PO-based invoices have nothing of their own to receive — "
                    + "receive against the originating purchase order instead: " + purchaseInvoiceId);
        }
        if (purchaseInvoice.isVoided()) {
            throw new IllegalArgumentException("Cannot receive against a voided purchase invoice: " + purchaseInvoiceId);
        }
        if (purchaseInvoice.isLoaded()) {
            throw new IllegalArgumentException("Purchase invoice already fully received: " + purchaseInvoiceId);
        }
        if (!purchaseInvoice.getWarehouse().getId().equals(warehouse.getId())) {
            throw new IllegalArgumentException("warehouseId does not match the purchase invoice's warehouse");
        }
        if (!purchaseInvoice.getSupplier().getId().equals(supplier.getId())) {
            throw new IllegalArgumentException("supplierId does not match the purchase invoice's supplier");
        }
        return purchaseInvoice;
    }

    private <L> boolean isFullyLoaded(List<L> lines, java.util.function.Function<L, BigDecimal> quantity,
                                       java.util.function.Function<L, BigDecimal> quantityLoaded) {
        return lines.stream().allMatch(l -> quantityLoaded.apply(l).compareTo(quantity.apply(l)) >= 0);
    }

    // Voiding is the only sanctioned way to cancel an immutable transaction (docs/TRANSACTIONS.md "Voiding").
    // Reverses the goods movement and un-loads the source (PO or Direct invoice) by the voided amount.
    @Transactional
    public PurchaseReceive voidPurchaseReceive(Long id, String username) {
        PurchaseReceive receive = findById(id, username);

        if (receive.isVoided()) {
            throw new IllegalArgumentException("Purchase receive already voided: " + id);
        }

        Instant now = Instant.now();

        for (PurchaseReceiveLine line : receive.getLines()) {
            postMovement(VOID_SOURCE_TYPE, receive.getCompany(), line.getItem(), receive.getWarehouse(),
                    line.getQuantity().negate(), receive.getReceiptDate(), receive.getId(),
                    receive.getReferenceNumber(), receive.getSheetNumber(), now, username);

            if (line.getPurchaseOrderLine() != null) {
                PurchaseOrderLine sourceLine = line.getPurchaseOrderLine();
                sourceLine.setQuantityLoaded(sourceLine.getQuantityLoaded().subtract(line.getQuantity()));
            } else {
                PurchaseInvoiceLine sourceLine = line.getPurchaseInvoiceLine();
                sourceLine.setQuantityLoaded(sourceLine.getQuantityLoaded().subtract(line.getQuantity()));
            }
        }

        if (receive.getPurchaseOrder() != null) {
            PurchaseOrder purchaseOrder = receive.getPurchaseOrder();
            purchaseOrder.setLoaded(isFullyLoaded(purchaseOrder.getLines(), PurchaseOrderLine::getQuantity, PurchaseOrderLine::getQuantityLoaded));
            purchaseOrderRepository.save(purchaseOrder);
        } else {
            PurchaseInvoice purchaseInvoice = receive.getPurchaseInvoice();
            purchaseInvoice.setLoaded(isFullyLoaded(purchaseInvoice.getLines(), PurchaseInvoiceLine::getQuantity, PurchaseInvoiceLine::getQuantityLoaded));
            purchaseInvoiceRepository.save(purchaseInvoice);
        }

        receive.setVoided(true);
        receive.setVoidedAt(now);
        receive.setVoidedBy(username);

        PurchaseReceive saved = purchaseReceiveRepository.save(receive);
        log.info("User '{}' voided purchase receive (id={})", username, id);
        return saved;
    }

    // Posts to both sides of the ledger at once: main Quantity increases, Transit Quantity decreases
    // by the same amount — mirrors PurchaseOrderService/PurchaseInvoiceService's postTransitMovement,
    // but this is the first transaction type posting a non-zero quantityDelta alongside the transit delta.
    private void postMovement(String sourceType, Company company, Item item, Warehouse warehouse, BigDecimal quantity,
                               Instant movementDate, Long receiveId, String referenceNumber, String sheetNumber,
                               Instant now, String username) {
        InventoryMovement movement = new InventoryMovement();
        movement.setCompany(company);
        movement.setItem(item);
        movement.setWarehouse(warehouse);
        movement.setQuantityDelta(quantity);
        movement.setTransitQuantityDelta(quantity.negate());
        movement.setMovementDate(movementDate);
        movement.setSourceType(sourceType);
        movement.setSourceId(receiveId);
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
        balance.setQuantity(balance.getQuantity().add(quantity));
        balance.setTransitQuantity(balance.getTransitQuantity().add(quantity.negate()));
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
