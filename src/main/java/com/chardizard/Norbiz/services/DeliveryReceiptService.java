package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.DeliveryReceiptLineRequest;
import com.chardizard.Norbiz.dto.DeliveryReceiptRequest;
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
public class DeliveryReceiptService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryReceiptService.class);
    private static final String TRANSACTION_TYPE = TransactionType.DELIVERY_RECEIPT.name();
    private static final String VOID_SOURCE_TYPE = "DELIVERY_RECEIPT_VOID";
    private static final String REFERENCE_PREFIX = "DR";

    private final DeliveryReceiptRepository deliveryReceiptRepository;
    private final StockTransferRepository stockTransferRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final InventoryStockService inventoryStockService;
    private final CompanyRepository companyRepository;
    private final CustomerRepository customerRepository;
    private final WarehouseRepository warehouseRepository;
    private final ItemRepository itemRepository;
    private final ItemPriceRepository itemPriceRepository;
    private final UserRepository userRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;

    public Page<DeliveryReceipt> findAllForUser(String username, Long customerId, Long warehouseId, Map<String, String> filters,
                                                 Instant dateFrom, Instant dateTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<DeliveryReceipt> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<DeliveryReceipt> customerScope = customerId == null ? null
                : (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId);
        Specification<DeliveryReceipt> warehouseScope = warehouseId == null ? null
                : (root, query, cb) -> cb.equal(root.get("warehouse").get("id"), warehouseId);

        Specification<DeliveryReceipt> spec = SpecificationUtils.allOf(
                companyScope,
                customerScope,
                warehouseScope,
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.dateRange("deliveryDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        );

        return deliveryReceiptRepository.findAll(spec, pageable);
    }

    public DeliveryReceipt findById(Long id, String username) {
        DeliveryReceipt receipt = deliveryReceiptRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Delivery receipt not found: " + id));
        assertCompanyAccess(username, receipt.getCompany().getId());
        return receipt;
    }

    @Transactional
    public DeliveryReceipt create(DeliveryReceiptRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + request.getCustomerId()));
        if (!customer.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Customer does not belong to company: " + company.getId());
        }
        if (!customer.isActive()) {
            throw new IllegalArgumentException("Customer is inactive: " + customer.getName());
        }

        Warehouse mainWarehouse = warehouseRepository.findFirstByCompanyIdAndMainTrue(company.getId())
                .orElseThrow(() -> new IllegalArgumentException("No main warehouse configured for company: " + company.getId()));
        if (!mainWarehouse.isActive()) {
            throw new IllegalArgumentException("Main warehouse is inactive: " + mainWarehouse.getName());
        }

        StockTransfer stockTransfer = request.getStockTransferId() == null ? null
                : loadAndValidateStockTransfer(request.getStockTransferId(), company, customer);
        boolean hasLines = request.getLines() != null && !request.getLines().isEmpty();
        if (stockTransfer != null && hasLines) {
            throw new IllegalArgumentException("lines must be omitted when delivering a stock transfer — they are copied from it");
        }
        if (stockTransfer == null && !hasLines) {
            throw new IllegalArgumentException("lines are required");
        }

        Warehouse outletWarehouse = null;
        if (customer.getType() == CustomerType.OUTLET) {
            outletWarehouse = customer.getWarehouse();
            if (outletWarehouse == null) {
                throw new IllegalArgumentException("Outlet has no warehouse to deliver into: " + customer.getName());
            }
        }

        Instant deliveryDate = DateRangeUtils.startOfDayUtc(request.getDeliveryDate());
        if (deliveryDate == null) {
            throw new IllegalArgumentException("deliveryDate is required (yyyy-MM-dd)");
        }

        Instant now = Instant.now();

        DeliveryReceipt receipt = new DeliveryReceipt();
        receipt.setCompany(company);
        receipt.setCustomer(customer);
        receipt.setWarehouse(mainWarehouse);
        receipt.setDestinationWarehouse(outletWarehouse);
        receipt.setStockTransfer(stockTransfer);
        receipt.setDeliveryDate(deliveryDate);
        receipt.setRemarks(request.getRemarks());
        receipt.setSheetNumber(request.getSheetNumber());
        receipt.setCreatedAt(now);
        receipt.setCreatedBy(username);

        if (stockTransfer != null) {
            // Loaded in full, 1:1 (like a PO-based Purchase Invoice): the transfer's lines are copied verbatim,
            // keeping their entry order. Exempt from the inactive-reference rule, since they were valid when held.
            for (StockTransferLine transferLine : stockTransfer.getLines()) {
                DeliveryReceiptLine line = new DeliveryReceiptLine();
                line.setDeliveryReceipt(receipt);
                line.setItem(transferLine.getItem());
                line.setQuantity(transferLine.getQuantity());
                line.setUnitPrice(transferLine.getUnitPrice());
                line.setLineNumber(transferLine.getLineNumber());
                receipt.getLines().add(line);
                transferLine.setQuantityLoaded(transferLine.getQuantity());
            }
            stockTransfer.setLoaded(true);
        }

        int lineNumber = 1;
        for (DeliveryReceiptLineRequest lineRequest : hasLines ? request.getLines() : List.<DeliveryReceiptLineRequest>of()) {
            Item item = itemRepository.findById(lineRequest.getItemId())
                    .orElseThrow(() -> new IllegalArgumentException("Item not found: " + lineRequest.getItemId()));
            if (!item.getCompany().getId().equals(company.getId())) {
                throw new IllegalArgumentException("Item does not belong to company: " + company.getId());
            }
            if (!item.getTags().contains(ItemTag.INVENTORY)) {
                throw new IllegalArgumentException("Item is not inventory-tracked: " + item.getItemCode());
            }
            if (!item.isActive()) {
                throw new IllegalArgumentException("Item is inactive: " + item.getItemCode());
            }

            DeliveryReceiptLine line = new DeliveryReceiptLine();
            line.setDeliveryReceipt(receipt);
            line.setItem(item);
            line.setQuantity(lineRequest.getQuantity());
            line.setUnitPrice(lineRequest.getUnitPrice() != null ? lineRequest.getUnitPrice() : currentUnitPrice(item));
            line.setLineNumber(lineNumber++);
            receipt.getLines().add(line);
        }

        // On-hand in the main warehouse can't go below zero (docs/INVENTORY.md "Negative stock").
        inventoryStockService.assertAvailable(mainWarehouse, receipt.getLines(), DeliveryReceiptLine::getItem,
                DeliveryReceiptLine::getQuantity);

        // Generated last, only once validation has fully passed, to avoid burning
        // reference numbers on requests that were always going to be rejected.
        receipt.setReferenceNumber(transactionReferenceService.next(company.getId(), TRANSACTION_TYPE, REFERENCE_PREFIX));

        DeliveryReceipt saved = deliveryReceiptRepository.save(receipt);
        transactionEventService.recordSystemEvent(company, TransactionType.DELIVERY_RECEIPT, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        for (DeliveryReceiptLine line : saved.getLines()) {
            postMovements(TRANSACTION_TYPE, saved, line, BigDecimal.ONE, now, username);
        }

        if (stockTransfer != null) stockTransferRepository.save(stockTransfer);

        log.info("User '{}' posted delivery receipt (id={}) with {} line(s) to customer {} from main warehouse {}{}{}",
                username, saved.getId(), saved.getLines().size(), customer.getId(), mainWarehouse.getId(),
                outletWarehouse != null ? " (in transit to outlet warehouse " + outletWarehouse.getId() + ")" : "",
                stockTransfer != null ? ", delivering stock transfer " + stockTransfer.getReferenceNumber() : "");
        return saved;
    }

    // Voiding is the only sanctioned way to cancel an immutable transaction (docs/TRANSACTIONS.md "Voiding").
    // Blocked once any Outlet Receive has loaded it — void those first.
    @Transactional
    public DeliveryReceipt voidDeliveryReceipt(Long id, String username) {
        DeliveryReceipt receipt = findById(id, username);

        if (receipt.isVoided()) {
            throw new IllegalArgumentException("Delivery receipt already voided: " + id);
        }
        boolean hasLoadedQuantity = receipt.getLines().stream()
                .anyMatch(l -> l.getQuantityLoaded().compareTo(BigDecimal.ZERO) > 0);
        if (receipt.isLoaded() || hasLoadedQuantity) {
            throw new IllegalArgumentException("Cannot void a delivery receipt that has been received by an outlet: " + id);
        }

        Instant now = Instant.now();
        for (DeliveryReceiptLine line : receipt.getLines()) {
            postMovements(VOID_SOURCE_TYPE, receipt, line, BigDecimal.ONE.negate(), now, username);
        }

        // Reopen the stock transfer it delivered: its hold on main-warehouse transit is back (reversed above).
        StockTransfer stockTransfer = receipt.getStockTransfer();
        if (stockTransfer != null) {
            stockTransfer.getLines().forEach(l -> l.setQuantityLoaded(BigDecimal.ZERO));
            stockTransfer.setLoaded(false);
            stockTransferRepository.save(stockTransfer);
        }

        receipt.setVoided(true);
        receipt.setVoidedAt(now);
        receipt.setVoidedBy(username);

        DeliveryReceipt saved = deliveryReceiptRepository.save(receipt);
        transactionEventService.recordSystemEvent(saved.getCompany(), TransactionType.DELIVERY_RECEIPT, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided delivery receipt (id={})", username, id);
        return saved;
    }

    private StockTransfer loadAndValidateStockTransfer(Long stockTransferId, Company company, Customer customer) {
        StockTransfer transfer = stockTransferRepository.findById(stockTransferId)
                .orElseThrow(() -> new IllegalArgumentException("Stock transfer not found: " + stockTransferId));
        if (!transfer.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Stock transfer does not belong to company: " + company.getId());
        }
        if (!transfer.getCustomer().getId().equals(customer.getId())) {
            throw new IllegalArgumentException("Stock transfer " + transfer.getReferenceNumber() + " is for a different customer");
        }
        if (transfer.isVoided()) {
            throw new IllegalArgumentException("Cannot deliver a voided stock transfer: " + stockTransferId);
        }
        if (transfer.isLoaded()) {
            throw new IllegalArgumentException("Stock transfer already delivered: " + stockTransferId);
        }
        return transfer;
    }

    private BigDecimal currentUnitPrice(Item item) {
        return itemPriceRepository.findByItemIdAndPriceType(item.getId(), PriceType.UNIT_PRICE)
                .map(ItemPrice::getAmount)
                .orElse(BigDecimal.ZERO);
    }

    // sign = +1 posts the delivery, -1 reverses it (void). The main warehouse loses on-hand stock; an
    // outlet's warehouse gains the same amount in transit, to be moved to on-hand by Outlet Receive.
    // A delivery of a Stock Transfer also gives back the transfer's negative transit hold in the main warehouse.
    private void postMovements(String sourceType, DeliveryReceipt receipt, DeliveryReceiptLine line, BigDecimal sign,
                               Instant now, String username) {
        BigDecimal quantity = line.getQuantity().multiply(sign);
        BigDecimal releasedHold = receipt.getStockTransfer() != null ? quantity : BigDecimal.ZERO;
        postMovement(sourceType, receipt, line.getItem(), receipt.getWarehouse(), quantity.negate(), releasedHold, now, username);
        if (receipt.getDestinationWarehouse() != null) {
            postMovement(sourceType, receipt, line.getItem(), receipt.getDestinationWarehouse(), BigDecimal.ZERO, quantity, now, username);
        }
    }

    private void postMovement(String sourceType, DeliveryReceipt receipt, Item item, Warehouse warehouse,
                              BigDecimal quantityDelta, BigDecimal transitQuantityDelta, Instant now, String username) {
        InventoryMovement movement = new InventoryMovement();
        movement.setCompany(receipt.getCompany());
        movement.setItem(item);
        movement.setWarehouse(warehouse);
        movement.setQuantityDelta(quantityDelta);
        movement.setTransitQuantityDelta(transitQuantityDelta);
        movement.setMovementDate(receipt.getDeliveryDate());
        movement.setSourceType(sourceType);
        movement.setSourceId(receipt.getId());
        movement.setReferenceNumber(receipt.getReferenceNumber());
        movement.setSheetNumber(receipt.getSheetNumber());
        movement.setCreatedAt(now);
        movement.setCreatedBy(username);
        inventoryMovementRepository.save(movement);

        inventoryStockService.apply(item, warehouse, quantityDelta, transitQuantityDelta, now);
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
