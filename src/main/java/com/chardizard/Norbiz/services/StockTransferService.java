package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.StockTransferLineRequest;
import com.chardizard.Norbiz.dto.StockTransferRequest;
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
import java.util.Optional;
import java.util.stream.Collectors;

// Sets main-warehouse stock aside for a customer ahead of delivery (docs/TRANSACTIONS.md "Stock Transfer").
// The hold is a negative transit posting in the main warehouse, copied from legacy jbsKarutora; the
// Delivery Receipt created from the transfer releases it (see DeliveryReceiptService).
@Service
@RequiredArgsConstructor
public class StockTransferService {

    private static final Logger log = LoggerFactory.getLogger(StockTransferService.class);
    private static final String TRANSACTION_TYPE = TransactionType.STOCK_TRANSFER.name();
    private static final String VOID_SOURCE_TYPE = "STOCK_TRANSFER_VOID";
    private static final String REFERENCE_PREFIX = "STF";

    private final StockTransferRepository stockTransferRepository;
    private final DeliveryReceiptRepository deliveryReceiptRepository;
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

    public Page<StockTransfer> findAllForUser(String username, Long customerId, Map<String, String> filters,
                                              Instant dateFrom, Instant dateTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<StockTransfer> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<StockTransfer> customerScope = customerId == null ? null
                : (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId);

        Specification<StockTransfer> spec = SpecificationUtils.allOf(
                companyScope,
                customerScope,
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.dateRange("transferDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        );

        return stockTransferRepository.findAll(spec, pageable);
    }

    public StockTransfer findById(Long id, String username) {
        StockTransfer transfer = stockTransferRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Stock transfer not found: " + id));
        assertCompanyAccess(username, transfer.getCompany().getId());
        return transfer;
    }

    /** The non-voided Delivery Receipt that loaded this transfer, if any. */
    public Optional<DeliveryReceipt> findDeliveryReceipt(StockTransfer transfer) {
        return deliveryReceiptRepository.findFirstByStockTransferIdAndVoidedFalse(transfer.getId());
    }

    @Transactional
    public StockTransfer create(StockTransferRequest request, String username) {
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

        Instant transferDate = DateRangeUtils.startOfDayUtc(request.getTransferDate());
        if (transferDate == null) {
            throw new IllegalArgumentException("transferDate is required (yyyy-MM-dd)");
        }

        Instant now = Instant.now();

        StockTransfer transfer = new StockTransfer();
        transfer.setCompany(company);
        transfer.setCustomer(customer);
        transfer.setWarehouse(mainWarehouse);
        transfer.setTransferDate(transferDate);
        transfer.setRemarks(request.getRemarks());
        transfer.setSheetNumber(request.getSheetNumber());
        transfer.setCreatedAt(now);
        transfer.setCreatedBy(username);

        int lineNumber = 1;
        for (StockTransferLineRequest lineRequest : request.getLines()) {
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

            StockTransferLine line = new StockTransferLine();
            line.setStockTransfer(transfer);
            line.setItem(item);
            line.setQuantity(lineRequest.getQuantity());
            line.setUnitPrice(lineRequest.getUnitPrice() != null ? lineRequest.getUnitPrice() : currentUnitPrice(item));
            line.setLineNumber(lineNumber++);
            transfer.getLines().add(line);
        }

        // Only stock actually on hand can be set aside (docs/INVENTORY.md "Negative stock").
        inventoryStockService.assertAvailable(mainWarehouse, transfer.getLines(), StockTransferLine::getItem,
                StockTransferLine::getQuantity);

        // Generated last, only once validation has fully passed, to avoid burning
        // reference numbers on requests that were always going to be rejected.
        transfer.setReferenceNumber(transactionReferenceService.next(company.getId(), TRANSACTION_TYPE, REFERENCE_PREFIX));

        StockTransfer saved = stockTransferRepository.save(transfer);
        transactionEventService.recordSystemEvent(company, TransactionType.STOCK_TRANSFER, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        for (StockTransferLine line : saved.getLines()) {
            postMovement(TRANSACTION_TYPE, saved, line.getItem(), line.getQuantity().negate(), now, username);
        }

        log.info("User '{}' posted stock transfer (id={}) with {} line(s) for customer {}, held in main warehouse {}",
                username, saved.getId(), saved.getLines().size(), customer.getId(), mainWarehouse.getId());
        return saved;
    }

    // Voiding is the only sanctioned way to cancel an immutable transaction (docs/TRANSACTIONS.md "Voiding").
    // Blocked once a Delivery Receipt has loaded it — void that first, which also releases this transfer.
    @Transactional
    public StockTransfer voidStockTransfer(Long id, String username) {
        StockTransfer transfer = findById(id, username);

        if (transfer.isVoided()) {
            throw new IllegalArgumentException("Stock transfer already voided: " + id);
        }
        if (transfer.isLoaded()) {
            throw new IllegalArgumentException("Cannot void a stock transfer that has been delivered: " + id);
        }

        Instant now = Instant.now();
        for (StockTransferLine line : transfer.getLines()) {
            postMovement(VOID_SOURCE_TYPE, transfer, line.getItem(), line.getQuantity(), now, username);
        }

        transfer.setVoided(true);
        transfer.setVoidedAt(now);
        transfer.setVoidedBy(username);

        StockTransfer saved = stockTransferRepository.save(transfer);
        transactionEventService.recordSystemEvent(saved.getCompany(), TransactionType.STOCK_TRANSFER, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided stock transfer (id={})", username, id);
        return saved;
    }

    private BigDecimal currentUnitPrice(Item item) {
        return itemPriceRepository.findByItemIdAndPriceType(item.getId(), PriceType.UNIT_PRICE)
                .map(ItemPrice::getAmount)
                .orElse(BigDecimal.ZERO);
    }

    // Transit-only: on-hand stays put until the Delivery Receipt actually ships it.
    private void postMovement(String sourceType, StockTransfer transfer, Item item, BigDecimal transitQuantityDelta,
                              Instant now, String username) {
        Warehouse warehouse = transfer.getWarehouse();

        InventoryMovement movement = new InventoryMovement();
        movement.setCompany(transfer.getCompany());
        movement.setItem(item);
        movement.setWarehouse(warehouse);
        movement.setQuantityDelta(BigDecimal.ZERO);
        movement.setTransitQuantityDelta(transitQuantityDelta);
        movement.setMovementDate(transfer.getTransferDate());
        movement.setSourceType(sourceType);
        movement.setSourceId(transfer.getId());
        movement.setReferenceNumber(transfer.getReferenceNumber());
        movement.setSheetNumber(transfer.getSheetNumber());
        movement.setCreatedAt(now);
        movement.setCreatedBy(username);
        inventoryMovementRepository.save(movement);

        inventoryStockService.apply(item, warehouse, BigDecimal.ZERO, transitQuantityDelta, now);
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
