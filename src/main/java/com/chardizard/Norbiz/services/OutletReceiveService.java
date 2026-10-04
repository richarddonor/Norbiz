package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.OutletReceiveLineRequest;
import com.chardizard.Norbiz.dto.OutletReceiveRequest;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OutletReceiveService {

    private static final Logger log = LoggerFactory.getLogger(OutletReceiveService.class);
    private static final String TRANSACTION_TYPE = TransactionType.OUTLET_RECEIVE.name();
    private static final String VOID_SOURCE_TYPE = "OUTLET_RECEIVE_VOID";
    private static final String REFERENCE_PREFIX = "OR";

    private final OutletReceiveRepository outletReceiveRepository;
    private final DeliveryReceiptRepository deliveryReceiptRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;

    public Page<OutletReceive> findAllForUser(String username, Long customerId, Long deliveryReceiptId, Map<String, String> filters,
                                               Instant dateFrom, Instant dateTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<OutletReceive> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<OutletReceive> customerScope = customerId == null ? null
                : (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId);
        Specification<OutletReceive> deliveryReceiptScope = deliveryReceiptId == null ? null
                : (root, query, cb) -> cb.equal(root.get("deliveryReceipt").get("id"), deliveryReceiptId);

        Specification<OutletReceive> spec = SpecificationUtils.allOf(
                companyScope,
                customerScope,
                deliveryReceiptScope,
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.dateRange("receiptDate", dateFrom, dateTo)
        );

        return outletReceiveRepository.findAll(spec, pageable);
    }

    public OutletReceive findById(Long id, String username) {
        OutletReceive receive = outletReceiveRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Outlet receive not found: " + id));
        assertCompanyAccess(username, receive.getCompany().getId());
        return receive;
    }

    @Transactional
    public OutletReceive create(OutletReceiveRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        DeliveryReceipt deliveryReceipt = loadAndValidateDeliveryReceipt(request.getDeliveryReceiptId(), company);

        Instant receiptDate = DateRangeUtils.startOfDayUtc(request.getReceiptDate());
        if (receiptDate == null) {
            throw new IllegalArgumentException("receiptDate is required (yyyy-MM-dd)");
        }

        Instant now = Instant.now();

        OutletReceive receive = new OutletReceive();
        receive.setCompany(company);
        receive.setDeliveryReceipt(deliveryReceipt);
        receive.setCustomer(deliveryReceipt.getCustomer());
        receive.setWarehouse(deliveryReceipt.getDestinationWarehouse());
        receive.setReceiptDate(receiptDate);
        receive.setRemarks(request.getRemarks());
        receive.setSheetNumber(request.getSheetNumber());
        receive.setCreatedAt(now);
        receive.setCreatedBy(username);

        // Same allocation as PurchaseReceiveService: a DR may carry more than one line for the same item,
        // so the requested quantity is spread across that item's lines oldest-first (by id), splitting into
        // several OutletReceiveLine rows (sharing the request's lineNumber) when it spans more than one.
        Map<Long, List<DeliveryReceiptLine>> linesByItemId = deliveryReceipt.getLines().stream()
                .sorted(Comparator.comparing(DeliveryReceiptLine::getId))
                .collect(Collectors.groupingBy(l -> l.getItem().getId()));

        int lineNumber = 1;
        for (OutletReceiveLineRequest lineRequest : request.getLines()) {
            List<DeliveryReceiptLine> sourceLines = linesByItemId.get(lineRequest.getItemId());
            if (sourceLines == null) {
                throw new IllegalArgumentException("Item " + lineRequest.getItemId() + " is not on delivery receipt: " + request.getDeliveryReceiptId());
            }
            BigDecimal totalOutstanding = sourceLines.stream()
                    .map(l -> l.getQuantity().subtract(l.getQuantityLoaded()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (lineRequest.getQuantity().compareTo(totalOutstanding) > 0) {
                throw new IllegalArgumentException("Quantity to receive (" + lineRequest.getQuantity()
                        + ") exceeds outstanding (" + totalOutstanding + ") for item: " + sourceLines.getFirst().getItem().getItemCode());
            }

            BigDecimal remaining = lineRequest.getQuantity();
            for (DeliveryReceiptLine sourceLine : sourceLines) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                BigDecimal outstanding = sourceLine.getQuantity().subtract(sourceLine.getQuantityLoaded());
                if (outstanding.compareTo(BigDecimal.ZERO) <= 0) continue;
                BigDecimal allocated = outstanding.min(remaining);

                OutletReceiveLine line = new OutletReceiveLine();
                line.setOutletReceive(receive);
                line.setItem(sourceLine.getItem());
                line.setDeliveryReceiptLine(sourceLine);
                line.setQuantity(allocated);
                line.setLineNumber(lineNumber);
                receive.getLines().add(line);

                sourceLine.setQuantityLoaded(sourceLine.getQuantityLoaded().add(allocated));
                remaining = remaining.subtract(allocated);
            }
            lineNumber++;
        }

        deliveryReceipt.setLoaded(isFullyLoaded(deliveryReceipt));

        // Generated last, only once validation has fully passed, to avoid burning
        // reference numbers on requests that were always going to be rejected.
        receive.setReferenceNumber(transactionReferenceService.next(company.getId(), TRANSACTION_TYPE, REFERENCE_PREFIX));

        OutletReceive saved = outletReceiveRepository.save(receive);
        transactionEventService.recordSystemEvent(company, TransactionType.OUTLET_RECEIVE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        for (OutletReceiveLine line : saved.getLines()) {
            postMovement(TRANSACTION_TYPE, saved, line.getItem(), line.getQuantity(), now, username);
        }

        deliveryReceiptRepository.save(deliveryReceipt);

        log.info("User '{}' posted outlet receive (id={}) with {} line(s) into outlet warehouse {} (against DR {}, now {})",
                username, saved.getId(), saved.getLines().size(), saved.getWarehouse().getId(),
                deliveryReceipt.getReferenceNumber(), deliveryReceipt.isLoaded() ? "fully received" : "partially received");
        return saved;
    }

    private DeliveryReceipt loadAndValidateDeliveryReceipt(Long deliveryReceiptId, Company company) {
        DeliveryReceipt deliveryReceipt = deliveryReceiptRepository.findById(deliveryReceiptId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery receipt not found: " + deliveryReceiptId));
        if (!deliveryReceipt.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Delivery receipt does not belong to company: " + company.getId());
        }
        if (deliveryReceipt.getDestinationWarehouse() == null) {
            throw new IllegalArgumentException("Delivery receipt to a non-outlet customer has nothing to receive: " + deliveryReceiptId);
        }
        if (deliveryReceipt.isVoided()) {
            throw new IllegalArgumentException("Cannot receive against a voided delivery receipt: " + deliveryReceiptId);
        }
        if (deliveryReceipt.isLoaded()) {
            throw new IllegalArgumentException("Delivery receipt already fully received: " + deliveryReceiptId);
        }
        return deliveryReceipt;
    }

    private boolean isFullyLoaded(DeliveryReceipt deliveryReceipt) {
        return deliveryReceipt.getLines().stream()
                .allMatch(l -> l.getQuantityLoaded().compareTo(l.getQuantity()) >= 0);
    }

    // Voiding is the only sanctioned way to cancel an immutable transaction (docs/TRANSACTIONS.md "Voiding").
    // Nothing loads from an Outlet Receive, so it's always voidable; it gives the amount back to transit
    // and un-loads the Delivery Receipt by the voided amounts.
    @Transactional
    public OutletReceive voidOutletReceive(Long id, String username) {
        OutletReceive receive = findById(id, username);

        if (receive.isVoided()) {
            throw new IllegalArgumentException("Outlet receive already voided: " + id);
        }

        Instant now = Instant.now();

        for (OutletReceiveLine line : receive.getLines()) {
            postMovement(VOID_SOURCE_TYPE, receive, line.getItem(), line.getQuantity().negate(), now, username);

            DeliveryReceiptLine sourceLine = line.getDeliveryReceiptLine();
            sourceLine.setQuantityLoaded(sourceLine.getQuantityLoaded().subtract(line.getQuantity()));
        }

        DeliveryReceipt deliveryReceipt = receive.getDeliveryReceipt();
        deliveryReceipt.setLoaded(isFullyLoaded(deliveryReceipt));
        deliveryReceiptRepository.save(deliveryReceipt);

        receive.setVoided(true);
        receive.setVoidedAt(now);
        receive.setVoidedBy(username);

        OutletReceive saved = outletReceiveRepository.save(receive);
        transactionEventService.recordSystemEvent(saved.getCompany(), TransactionType.OUTLET_RECEIVE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided outlet receive (id={})", username, id);
        return saved;
    }

    // Both sides of the outlet warehouse's ledger at once: on-hand up, in-transit down (mirrors Purchase Receive).
    private void postMovement(String sourceType, OutletReceive receive, Item item, BigDecimal quantity, Instant now, String username) {
        Warehouse warehouse = receive.getWarehouse();

        InventoryMovement movement = new InventoryMovement();
        movement.setCompany(receive.getCompany());
        movement.setItem(item);
        movement.setWarehouse(warehouse);
        movement.setQuantityDelta(quantity);
        movement.setTransitQuantityDelta(quantity.negate());
        movement.setMovementDate(receive.getReceiptDate());
        movement.setSourceType(sourceType);
        movement.setSourceId(receive.getId());
        movement.setReferenceNumber(receive.getReferenceNumber());
        movement.setSheetNumber(receive.getSheetNumber());
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
