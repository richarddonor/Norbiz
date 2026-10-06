package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.OutletDeliveryReceiptLineRequest;
import com.chardizard.Norbiz.dto.OutletDeliveryReceiptRequest;
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
public class OutletDeliveryReceiptService {

    private static final Logger log = LoggerFactory.getLogger(OutletDeliveryReceiptService.class);
    private static final String TRANSACTION_TYPE = TransactionType.OUTLET_DELIVERY_RECEIPT.name();
    private static final String VOID_SOURCE_TYPE = "OUTLET_DELIVERY_RECEIPT_VOID";
    private static final String REFERENCE_PREFIX = "ODR";

    private final OutletDeliveryReceiptRepository outletDeliveryReceiptRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final InventoryStockService inventoryStockService;
    private final CompanyRepository companyRepository;
    private final CustomerRepository customerRepository;
    private final EmployeeRepository employeeRepository;
    private final ItemRepository itemRepository;
    private final ItemPriceRepository itemPriceRepository;
    private final UserRepository userRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;

    public Page<OutletDeliveryReceipt> findAllForUser(String username, Long customerId, Long agentId, Map<String, String> filters,
                                                       Instant dateFrom, Instant dateTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<OutletDeliveryReceipt> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<OutletDeliveryReceipt> customerScope = customerId == null ? null
                : (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId);
        Specification<OutletDeliveryReceipt> agentScope = agentId == null ? null
                : (root, query, cb) -> cb.equal(root.get("agent").get("id"), agentId);

        Specification<OutletDeliveryReceipt> spec = SpecificationUtils.allOf(
                companyScope,
                customerScope,
                agentScope,
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.dateRange("deliveryDate", dateFrom, dateTo)
        );

        return outletDeliveryReceiptRepository.findAll(spec, pageable);
    }

    public OutletDeliveryReceipt findById(Long id, String username) {
        OutletDeliveryReceipt receipt = outletDeliveryReceiptRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Outlet delivery receipt not found: " + id));
        assertCompanyAccess(username, receipt.getCompany().getId());
        return receipt;
    }

    @Transactional
    public OutletDeliveryReceipt create(OutletDeliveryReceiptRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        Customer outlet = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + request.getCustomerId()));
        if (!outlet.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Customer does not belong to company: " + company.getId());
        }
        if (outlet.getType() != CustomerType.OUTLET) {
            throw new IllegalArgumentException("Customer is not an outlet: " + outlet.getName());
        }
        if (!outlet.isActive()) {
            throw new IllegalArgumentException("Outlet is inactive: " + outlet.getName());
        }
        Warehouse outletWarehouse = outlet.getWarehouse();
        if (outletWarehouse == null) {
            throw new IllegalArgumentException("Outlet has no warehouse to sell from: " + outlet.getName());
        }

        Employee agent = employeeRepository.findById(request.getAgentId())
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + request.getAgentId()));
        if (!agent.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Agent does not belong to company: " + company.getId());
        }
        if (!agent.isActive()) {
            throw new IllegalArgumentException("Agent is inactive: " + agent.getEmployeeCode());
        }
        if (!agent.getTags().contains(EmployeeTag.AGENT)) {
            throw new IllegalArgumentException("Employee is not tagged as an agent: " + agent.getEmployeeCode());
        }

        Instant deliveryDate = DateRangeUtils.startOfDayUtc(request.getDeliveryDate());
        if (deliveryDate == null) {
            throw new IllegalArgumentException("deliveryDate is required (yyyy-MM-dd)");
        }

        Instant now = Instant.now();

        OutletDeliveryReceipt receipt = new OutletDeliveryReceipt();
        receipt.setCompany(company);
        receipt.setCustomer(outlet);
        receipt.setWarehouse(outletWarehouse);
        receipt.setAgent(agent);
        receipt.setDeliveryDate(deliveryDate);
        receipt.setRemarks(request.getRemarks());
        receipt.setSheetNumber(request.getSheetNumber());
        receipt.setCreatedAt(now);
        receipt.setCreatedBy(username);

        int lineNumber = 1;
        for (OutletDeliveryReceiptLineRequest lineRequest : request.getLines()) {
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

            OutletDeliveryReceiptLine line = new OutletDeliveryReceiptLine();
            line.setOutletDeliveryReceipt(receipt);
            line.setItem(item);
            line.setQuantity(lineRequest.getQuantity());
            line.setUnitPrice(lineRequest.getUnitPrice() != null ? lineRequest.getUnitPrice() : currentUnitPrice(item));
            line.setLineNumber(lineNumber++);
            receipt.getLines().add(line);
        }

        // On-hand in the outlet's warehouse can't go below zero (docs/INVENTORY.md "Negative stock").
        inventoryStockService.assertAvailable(outletWarehouse, receipt.getLines(), OutletDeliveryReceiptLine::getItem,
                OutletDeliveryReceiptLine::getQuantity);

        // Generated last, only once validation has fully passed, to avoid burning
        // reference numbers on requests that were always going to be rejected.
        receipt.setReferenceNumber(transactionReferenceService.next(company.getId(), TRANSACTION_TYPE, REFERENCE_PREFIX));

        OutletDeliveryReceipt saved = outletDeliveryReceiptRepository.save(receipt);
        transactionEventService.recordSystemEvent(company, TransactionType.OUTLET_DELIVERY_RECEIPT, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        for (OutletDeliveryReceiptLine line : saved.getLines()) {
            postMovement(TRANSACTION_TYPE, saved, line.getItem(), line.getQuantity().negate(), now, username);
        }

        log.info("User '{}' posted outlet delivery receipt (id={}) with {} line(s) from outlet {} warehouse {}, agent {}",
                username, saved.getId(), saved.getLines().size(), outlet.getId(), outletWarehouse.getId(), agent.getId());
        return saved;
    }

    // Voiding is the only sanctioned way to cancel an immutable transaction (docs/TRANSACTIONS.md "Voiding").
    // Blocked once any Outlet Delivery Return has loaded it — void those first.
    @Transactional
    public OutletDeliveryReceipt voidOutletDeliveryReceipt(Long id, String username) {
        OutletDeliveryReceipt receipt = findById(id, username);

        if (receipt.isVoided()) {
            throw new IllegalArgumentException("Outlet delivery receipt already voided: " + id);
        }
        boolean hasLoadedQuantity = receipt.getLines().stream()
                .anyMatch(l -> l.getQuantityLoaded().compareTo(BigDecimal.ZERO) > 0);
        if (receipt.isLoaded() || hasLoadedQuantity) {
            throw new IllegalArgumentException("Cannot void an outlet delivery receipt that has returns: " + id);
        }

        Instant now = Instant.now();
        for (OutletDeliveryReceiptLine line : receipt.getLines()) {
            postMovement(VOID_SOURCE_TYPE, receipt, line.getItem(), line.getQuantity(), now, username);
        }

        receipt.setVoided(true);
        receipt.setVoidedAt(now);
        receipt.setVoidedBy(username);

        OutletDeliveryReceipt saved = outletDeliveryReceiptRepository.save(receipt);
        transactionEventService.recordSystemEvent(saved.getCompany(), TransactionType.OUTLET_DELIVERY_RECEIPT, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided outlet delivery receipt (id={})", username, id);
        return saved;
    }

    private BigDecimal currentUnitPrice(Item item) {
        return itemPriceRepository.findByItemIdAndPriceType(item.getId(), PriceType.UNIT_PRICE)
                .map(ItemPrice::getAmount)
                .orElse(BigDecimal.ZERO);
    }

    // On-hand only, in the outlet's warehouse: negative on sale, positive on void.
    private void postMovement(String sourceType, OutletDeliveryReceipt receipt, Item item, BigDecimal quantityDelta,
                              Instant now, String username) {
        Warehouse warehouse = receipt.getWarehouse();

        InventoryMovement movement = new InventoryMovement();
        movement.setCompany(receipt.getCompany());
        movement.setItem(item);
        movement.setWarehouse(warehouse);
        movement.setQuantityDelta(quantityDelta);
        movement.setTransitQuantityDelta(BigDecimal.ZERO);
        movement.setMovementDate(receipt.getDeliveryDate());
        movement.setSourceType(sourceType);
        movement.setSourceId(receipt.getId());
        movement.setReferenceNumber(receipt.getReferenceNumber());
        movement.setSheetNumber(receipt.getSheetNumber());
        movement.setCreatedAt(now);
        movement.setCreatedBy(username);
        inventoryMovementRepository.save(movement);

        inventoryStockService.apply(item, warehouse, quantityDelta, BigDecimal.ZERO, now);
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
