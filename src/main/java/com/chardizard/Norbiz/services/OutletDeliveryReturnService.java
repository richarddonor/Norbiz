package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.OutletDeliveryReturnLineRequest;
import com.chardizard.Norbiz.dto.OutletDeliveryReturnRequest;
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
public class OutletDeliveryReturnService {

    private static final Logger log = LoggerFactory.getLogger(OutletDeliveryReturnService.class);
    private static final String TRANSACTION_TYPE = TransactionType.OUTLET_DELIVERY_RETURN.name();
    private static final String VOID_SOURCE_TYPE = "OUTLET_DELIVERY_RETURN_VOID";
    private static final String REFERENCE_PREFIX = "ODRR";

    private final OutletDeliveryReturnRepository outletDeliveryReturnRepository;
    private final OutletDeliveryReceiptRepository outletDeliveryReceiptRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final InventoryStockService inventoryStockService;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;

    public Page<OutletDeliveryReturn> findAllForUser(String username, Long customerId, Long agentId, Long outletDeliveryReceiptId,
                                                      Map<String, String> filters, Instant dateFrom, Instant dateTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<OutletDeliveryReturn> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<OutletDeliveryReturn> customerScope = customerId == null ? null
                : (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId);
        Specification<OutletDeliveryReturn> agentScope = agentId == null ? null
                : (root, query, cb) -> cb.equal(root.get("agent").get("id"), agentId);
        Specification<OutletDeliveryReturn> sourceScope = outletDeliveryReceiptId == null ? null
                : (root, query, cb) -> cb.equal(root.get("outletDeliveryReceipt").get("id"), outletDeliveryReceiptId);

        Specification<OutletDeliveryReturn> spec = SpecificationUtils.allOf(
                companyScope,
                customerScope,
                agentScope,
                sourceScope,
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.dateRange("returnDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        );

        return outletDeliveryReturnRepository.findAll(spec, pageable);
    }

    public OutletDeliveryReturn findById(Long id, String username) {
        OutletDeliveryReturn ret = outletDeliveryReturnRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Outlet delivery return not found: " + id));
        assertCompanyAccess(username, ret.getCompany().getId());
        return ret;
    }

    @Transactional
    public OutletDeliveryReturn create(OutletDeliveryReturnRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        OutletDeliveryReceipt source = loadAndValidateSource(request.getOutletDeliveryReceiptId(), company);

        Instant returnDate = DateRangeUtils.startOfDayUtc(request.getReturnDate());
        if (returnDate == null) {
            throw new IllegalArgumentException("returnDate is required (yyyy-MM-dd)");
        }

        Instant now = Instant.now();

        OutletDeliveryReturn ret = new OutletDeliveryReturn();
        ret.setCompany(company);
        ret.setOutletDeliveryReceipt(source);
        ret.setCustomer(source.getCustomer());
        ret.setWarehouse(source.getWarehouse());
        ret.setAgent(source.getAgent());
        ret.setReturnDate(returnDate);
        ret.setRemarks(request.getRemarks());
        ret.setSheetNumber(request.getSheetNumber());
        ret.setCreatedAt(now);
        ret.setCreatedBy(username);

        // Same allocation as OutletReceiveService: an ODR may carry more than one line for the same item,
        // so the requested quantity is spread across that item's lines oldest-first (by id), splitting into
        // several return lines (sharing the request's lineNumber) when it spans more than one.
        Map<Long, List<OutletDeliveryReceiptLine>> linesByItemId = source.getLines().stream()
                .sorted(Comparator.comparing(OutletDeliveryReceiptLine::getId))
                .collect(Collectors.groupingBy(l -> l.getItem().getId()));

        int lineNumber = 1;
        for (OutletDeliveryReturnLineRequest lineRequest : request.getLines()) {
            List<OutletDeliveryReceiptLine> sourceLines = linesByItemId.get(lineRequest.getItemId());
            if (sourceLines == null) {
                throw new IllegalArgumentException("Item " + lineRequest.getItemId() + " is not on outlet delivery receipt: "
                        + request.getOutletDeliveryReceiptId());
            }
            BigDecimal totalOutstanding = sourceLines.stream()
                    .map(l -> l.getQuantity().subtract(l.getQuantityLoaded()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (lineRequest.getQuantity().compareTo(totalOutstanding) > 0) {
                throw new IllegalArgumentException("Quantity to return (" + lineRequest.getQuantity()
                        + ") exceeds outstanding (" + totalOutstanding + ") for item: " + sourceLines.getFirst().getItem().getItemCode());
            }

            BigDecimal remaining = lineRequest.getQuantity();
            for (OutletDeliveryReceiptLine sourceLine : sourceLines) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                BigDecimal outstanding = sourceLine.getQuantity().subtract(sourceLine.getQuantityLoaded());
                if (outstanding.compareTo(BigDecimal.ZERO) <= 0) continue;
                BigDecimal allocated = outstanding.min(remaining);

                OutletDeliveryReturnLine line = new OutletDeliveryReturnLine();
                line.setOutletDeliveryReturn(ret);
                line.setItem(sourceLine.getItem());
                line.setOutletDeliveryReceiptLine(sourceLine);
                line.setQuantity(allocated);
                line.setUnitPrice(sourceLine.getUnitPrice());
                line.setLineNumber(lineNumber);
                ret.getLines().add(line);

                sourceLine.setQuantityLoaded(sourceLine.getQuantityLoaded().add(allocated));
                remaining = remaining.subtract(allocated);
            }
            lineNumber++;
        }

        source.setLoaded(isFullyLoaded(source));

        // Generated last, only once validation has fully passed, to avoid burning
        // reference numbers on requests that were always going to be rejected.
        ret.setReferenceNumber(transactionReferenceService.next(company.getId(), TRANSACTION_TYPE, REFERENCE_PREFIX));

        OutletDeliveryReturn saved = outletDeliveryReturnRepository.save(ret);
        transactionEventService.recordSystemEvent(company, TransactionType.OUTLET_DELIVERY_RETURN, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        for (OutletDeliveryReturnLine line : saved.getLines()) {
            postMovement(TRANSACTION_TYPE, saved, line.getItem(), line.getQuantity(), now, username);
        }

        outletDeliveryReceiptRepository.save(source);

        log.info("User '{}' posted outlet delivery return (id={}) with {} line(s) into outlet warehouse {} (against ODR {}, now {})",
                username, saved.getId(), saved.getLines().size(), saved.getWarehouse().getId(),
                source.getReferenceNumber(), source.isLoaded() ? "fully returned" : "partially returned");
        return saved;
    }

    private OutletDeliveryReceipt loadAndValidateSource(Long outletDeliveryReceiptId, Company company) {
        OutletDeliveryReceipt source = outletDeliveryReceiptRepository.findById(outletDeliveryReceiptId)
                .orElseThrow(() -> new IllegalArgumentException("Outlet delivery receipt not found: " + outletDeliveryReceiptId));
        if (!source.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Outlet delivery receipt does not belong to company: " + company.getId());
        }
        if (source.isVoided()) {
            throw new IllegalArgumentException("Cannot return against a voided outlet delivery receipt: " + outletDeliveryReceiptId);
        }
        if (source.isLoaded()) {
            throw new IllegalArgumentException("Outlet delivery receipt already fully returned: " + outletDeliveryReceiptId);
        }
        return source;
    }

    private boolean isFullyLoaded(OutletDeliveryReceipt source) {
        return source.getLines().stream()
                .allMatch(l -> l.getQuantityLoaded().compareTo(l.getQuantity()) >= 0);
    }

    // Voiding is the only sanctioned way to cancel an immutable transaction (docs/TRANSACTIONS.md "Voiding").
    // Nothing loads from a return, so it's always voidable; it takes the stock back out of the outlet
    // warehouse and reopens the Outlet Delivery Receipt by the voided amounts.
    @Transactional
    public OutletDeliveryReturn voidOutletDeliveryReturn(Long id, String username) {
        OutletDeliveryReturn ret = findById(id, username);

        if (ret.isVoided()) {
            throw new IllegalArgumentException("Outlet delivery return already voided: " + id);
        }

        // Voiding takes the returned stock back out of on-hand, which may since have been sold again.
        inventoryStockService.assertAvailable(ret.getWarehouse(), ret.getLines(), OutletDeliveryReturnLine::getItem,
                OutletDeliveryReturnLine::getQuantity);

        Instant now = Instant.now();

        for (OutletDeliveryReturnLine line : ret.getLines()) {
            postMovement(VOID_SOURCE_TYPE, ret, line.getItem(), line.getQuantity().negate(), now, username);

            OutletDeliveryReceiptLine sourceLine = line.getOutletDeliveryReceiptLine();
            sourceLine.setQuantityLoaded(sourceLine.getQuantityLoaded().subtract(line.getQuantity()));
        }

        OutletDeliveryReceipt source = ret.getOutletDeliveryReceipt();
        source.setLoaded(isFullyLoaded(source));
        outletDeliveryReceiptRepository.save(source);

        ret.setVoided(true);
        ret.setVoidedAt(now);
        ret.setVoidedBy(username);

        OutletDeliveryReturn saved = outletDeliveryReturnRepository.save(ret);
        transactionEventService.recordSystemEvent(saved.getCompany(), TransactionType.OUTLET_DELIVERY_RETURN, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided outlet delivery return (id={})", username, id);
        return saved;
    }

    // On-hand only, in the outlet's warehouse: positive on return, negative on void.
    private void postMovement(String sourceType, OutletDeliveryReturn ret, Item item, BigDecimal quantityDelta, Instant now, String username) {
        Warehouse warehouse = ret.getWarehouse();

        InventoryMovement movement = new InventoryMovement();
        movement.setCompany(ret.getCompany());
        movement.setItem(item);
        movement.setWarehouse(warehouse);
        movement.setQuantityDelta(quantityDelta);
        movement.setTransitQuantityDelta(BigDecimal.ZERO);
        movement.setMovementDate(ret.getReturnDate());
        movement.setSourceType(sourceType);
        movement.setSourceId(ret.getId());
        movement.setReferenceNumber(ret.getReferenceNumber());
        movement.setSheetNumber(ret.getSheetNumber());
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
