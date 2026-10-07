package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.OutletPullOutLineRequest;
import com.chardizard.Norbiz.dto.OutletPullOutRequest;
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

// Pulls stock out of an outlet back to the company (docs/TRANSACTIONS.md "Outlet Pull Out") — the reverse of a
// Delivery Receipt: on-hand leaves the outlet's warehouse and arrives in transit in the main warehouse, to be
// taken into on-hand there by Pull Out Receive.
@Service
@RequiredArgsConstructor
public class OutletPullOutService {

    private static final Logger log = LoggerFactory.getLogger(OutletPullOutService.class);
    private static final String TRANSACTION_TYPE = TransactionType.OUTLET_PULL_OUT.name();
    private static final String VOID_SOURCE_TYPE = "OUTLET_PULL_OUT_VOID";
    private static final String REFERENCE_PREFIX = "DRR";

    private final OutletPullOutRepository outletPullOutRepository;
    private final PullOutReasonRepository pullOutReasonRepository;
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

    public Page<OutletPullOut> findAllForUser(String username, Long customerId, Long pullOutReasonId, Map<String, String> filters,
                                              Instant dateFrom, Instant dateTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<OutletPullOut> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<OutletPullOut> customerScope = customerId == null ? null
                : (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId);
        Specification<OutletPullOut> reasonScope = pullOutReasonId == null ? null
                : (root, query, cb) -> cb.equal(root.get("reason").get("id"), pullOutReasonId);

        Specification<OutletPullOut> spec = SpecificationUtils.allOf(
                companyScope,
                customerScope,
                reasonScope,
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.dateRange("pullOutDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        );

        return outletPullOutRepository.findAll(spec, pageable);
    }

    public OutletPullOut findById(Long id, String username) {
        OutletPullOut pullOut = outletPullOutRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Outlet pull out not found: " + id));
        assertCompanyAccess(username, pullOut.getCompany().getId());
        return pullOut;
    }

    @Transactional
    public OutletPullOut create(OutletPullOutRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        Customer outlet = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + request.getCustomerId()));
        if (!outlet.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Customer does not belong to company: " + company.getId());
        }
        if (outlet.getType() != CustomerType.OUTLET || outlet.getWarehouse() == null) {
            throw new IllegalArgumentException("Only an outlet with its own warehouse can be pulled out from: " + outlet.getName());
        }
        if (!outlet.isActive()) {
            throw new IllegalArgumentException("Outlet is inactive: " + outlet.getName());
        }

        PullOutReason reason = pullOutReasonRepository.findById(request.getPullOutReasonId())
                .orElseThrow(() -> new IllegalArgumentException("Pull out reason not found: " + request.getPullOutReasonId()));
        if (!reason.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Pull out reason does not belong to company: " + company.getId());
        }
        if (!reason.isActive()) {
            throw new IllegalArgumentException("Pull out reason is inactive: " + reason.getName());
        }

        Warehouse mainWarehouse = warehouseRepository.findFirstByCompanyIdAndMainTrue(company.getId())
                .orElseThrow(() -> new IllegalArgumentException("No main warehouse configured for company: " + company.getId()));
        if (!mainWarehouse.isActive()) {
            throw new IllegalArgumentException("Main warehouse is inactive: " + mainWarehouse.getName());
        }

        Instant pullOutDate = DateRangeUtils.startOfDayUtc(request.getPullOutDate());
        if (pullOutDate == null) {
            throw new IllegalArgumentException("pullOutDate is required (yyyy-MM-dd)");
        }

        Instant now = Instant.now();

        OutletPullOut pullOut = new OutletPullOut();
        pullOut.setCompany(company);
        pullOut.setCustomer(outlet);
        pullOut.setWarehouse(outlet.getWarehouse());
        pullOut.setDestinationWarehouse(mainWarehouse);
        pullOut.setReason(reason);
        pullOut.setPullOutDate(pullOutDate);
        pullOut.setRemarks(request.getRemarks());
        pullOut.setSheetNumber(request.getSheetNumber());
        pullOut.setCreatedAt(now);
        pullOut.setCreatedBy(username);

        int lineNumber = 1;
        for (OutletPullOutLineRequest lineRequest : request.getLines()) {
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

            OutletPullOutLine line = new OutletPullOutLine();
            line.setOutletPullOut(pullOut);
            line.setItem(item);
            line.setQuantity(lineRequest.getQuantity());
            line.setUnitPrice(lineRequest.getUnitPrice() != null ? lineRequest.getUnitPrice() : currentUnitPrice(item));
            line.setLineNumber(lineNumber++);
            pullOut.getLines().add(line);
        }

        // On-hand in the outlet's warehouse can't go below zero (docs/INVENTORY.md "Negative stock").
        inventoryStockService.assertAvailable(pullOut.getWarehouse(), pullOut.getLines(), OutletPullOutLine::getItem,
                OutletPullOutLine::getQuantity);

        // Generated last, only once validation has fully passed, to avoid burning
        // reference numbers on requests that were always going to be rejected.
        pullOut.setReferenceNumber(transactionReferenceService.next(company.getId(), TRANSACTION_TYPE, REFERENCE_PREFIX));

        OutletPullOut saved = outletPullOutRepository.save(pullOut);
        transactionEventService.recordSystemEvent(company, TransactionType.OUTLET_PULL_OUT, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        for (OutletPullOutLine line : saved.getLines()) {
            postMovements(TRANSACTION_TYPE, saved, line, BigDecimal.ONE, now, username);
        }

        log.info("User '{}' posted outlet pull out (id={}) with {} line(s) from outlet warehouse {} (in transit to main warehouse {})",
                username, saved.getId(), saved.getLines().size(), saved.getWarehouse().getId(), mainWarehouse.getId());
        return saved;
    }

    // Voiding is the only sanctioned way to cancel an immutable transaction (docs/TRANSACTIONS.md "Voiding").
    // Blocked once any Pull Out Receive has loaded it — void those first.
    @Transactional
    public OutletPullOut voidOutletPullOut(Long id, String username) {
        OutletPullOut pullOut = findById(id, username);

        if (pullOut.isVoided()) {
            throw new IllegalArgumentException("Outlet pull out already voided: " + id);
        }
        boolean hasLoadedQuantity = pullOut.getLines().stream()
                .anyMatch(l -> l.getQuantityLoaded().compareTo(BigDecimal.ZERO) > 0);
        if (pullOut.isLoaded() || hasLoadedQuantity) {
            throw new IllegalArgumentException("Cannot void an outlet pull out that has been received: " + id);
        }

        Instant now = Instant.now();
        for (OutletPullOutLine line : pullOut.getLines()) {
            postMovements(VOID_SOURCE_TYPE, pullOut, line, BigDecimal.ONE.negate(), now, username);
        }

        pullOut.setVoided(true);
        pullOut.setVoidedAt(now);
        pullOut.setVoidedBy(username);

        OutletPullOut saved = outletPullOutRepository.save(pullOut);
        transactionEventService.recordSystemEvent(saved.getCompany(), TransactionType.OUTLET_PULL_OUT, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided outlet pull out (id={})", username, id);
        return saved;
    }

    private BigDecimal currentUnitPrice(Item item) {
        return itemPriceRepository.findByItemIdAndPriceType(item.getId(), PriceType.UNIT_PRICE)
                .map(ItemPrice::getAmount)
                .orElse(BigDecimal.ZERO);
    }

    // sign = +1 posts the pull out, -1 reverses it (void): the outlet loses on-hand stock and the main
    // warehouse gains the same amount in transit.
    private void postMovements(String sourceType, OutletPullOut pullOut, OutletPullOutLine line, BigDecimal sign,
                               Instant now, String username) {
        BigDecimal quantity = line.getQuantity().multiply(sign);
        postMovement(sourceType, pullOut, line.getItem(), pullOut.getWarehouse(), quantity.negate(), BigDecimal.ZERO, now, username);
        postMovement(sourceType, pullOut, line.getItem(), pullOut.getDestinationWarehouse(), BigDecimal.ZERO, quantity, now, username);
    }

    private void postMovement(String sourceType, OutletPullOut pullOut, Item item, Warehouse warehouse,
                              BigDecimal quantityDelta, BigDecimal transitQuantityDelta, Instant now, String username) {
        InventoryMovement movement = new InventoryMovement();
        movement.setCompany(pullOut.getCompany());
        movement.setItem(item);
        movement.setWarehouse(warehouse);
        movement.setQuantityDelta(quantityDelta);
        movement.setTransitQuantityDelta(transitQuantityDelta);
        movement.setMovementDate(pullOut.getPullOutDate());
        movement.setSourceType(sourceType);
        movement.setSourceId(pullOut.getId());
        movement.setReferenceNumber(pullOut.getReferenceNumber());
        movement.setSheetNumber(pullOut.getSheetNumber());
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
