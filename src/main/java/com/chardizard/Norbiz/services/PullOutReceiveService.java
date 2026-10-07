package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.PullOutReceiveLineRequest;
import com.chardizard.Norbiz.dto.PullOutReceiveRequest;
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

// Receives an Outlet Pull Out's in-transit quantity into the main warehouse's on-hand stock
// (docs/TRANSACTIONS.md "Pull Out Receive") — the reverse of Outlet Receive. Supports partial receiving.
@Service
@RequiredArgsConstructor
public class PullOutReceiveService {

    private static final Logger log = LoggerFactory.getLogger(PullOutReceiveService.class);
    private static final String TRANSACTION_TYPE = TransactionType.PULL_OUT_RECEIVE.name();
    private static final String VOID_SOURCE_TYPE = "PULL_OUT_RECEIVE_VOID";
    private static final String REFERENCE_PREFIX = "OPO";

    private final PullOutReceiveRepository pullOutReceiveRepository;
    private final OutletPullOutRepository outletPullOutRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final InventoryStockService inventoryStockService;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;

    public Page<PullOutReceive> findAllForUser(String username, Long customerId, Long outletPullOutId, Map<String, String> filters,
                                               Instant dateFrom, Instant dateTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<PullOutReceive> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<PullOutReceive> customerScope = customerId == null ? null
                : (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId);
        Specification<PullOutReceive> outletPullOutScope = outletPullOutId == null ? null
                : (root, query, cb) -> cb.equal(root.get("outletPullOut").get("id"), outletPullOutId);

        Specification<PullOutReceive> spec = SpecificationUtils.allOf(
                companyScope,
                customerScope,
                outletPullOutScope,
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.dateRange("receiptDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        );

        return pullOutReceiveRepository.findAll(spec, pageable);
    }

    public PullOutReceive findById(Long id, String username) {
        PullOutReceive receive = pullOutReceiveRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Pull out receive not found: " + id));
        assertCompanyAccess(username, receive.getCompany().getId());
        return receive;
    }

    @Transactional
    public PullOutReceive create(PullOutReceiveRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        OutletPullOut outletPullOut = loadAndValidateOutletPullOut(request.getOutletPullOutId(), company);

        Instant receiptDate = DateRangeUtils.startOfDayUtc(request.getReceiptDate());
        if (receiptDate == null) {
            throw new IllegalArgumentException("receiptDate is required (yyyy-MM-dd)");
        }

        Instant now = Instant.now();

        PullOutReceive receive = new PullOutReceive();
        receive.setCompany(company);
        receive.setOutletPullOut(outletPullOut);
        receive.setCustomer(outletPullOut.getCustomer());
        receive.setWarehouse(outletPullOut.getDestinationWarehouse());
        receive.setReceiptDate(receiptDate);
        receive.setRemarks(request.getRemarks());
        receive.setSheetNumber(request.getSheetNumber());
        receive.setCreatedAt(now);
        receive.setCreatedBy(username);

        // Same allocation as OutletReceiveService: a pull out may carry more than one line for the same item,
        // so the requested quantity is spread across that item's lines oldest-first (by id), splitting into
        // several PullOutReceiveLine rows (sharing the request's lineNumber) when it spans more than one.
        Map<Long, List<OutletPullOutLine>> linesByItemId = outletPullOut.getLines().stream()
                .sorted(Comparator.comparing(OutletPullOutLine::getId))
                .collect(Collectors.groupingBy(l -> l.getItem().getId()));

        int lineNumber = 1;
        for (PullOutReceiveLineRequest lineRequest : request.getLines()) {
            List<OutletPullOutLine> sourceLines = linesByItemId.get(lineRequest.getItemId());
            if (sourceLines == null) {
                throw new IllegalArgumentException("Item " + lineRequest.getItemId() + " is not on outlet pull out: " + request.getOutletPullOutId());
            }
            BigDecimal totalOutstanding = sourceLines.stream()
                    .map(l -> l.getQuantity().subtract(l.getQuantityLoaded()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (lineRequest.getQuantity().compareTo(totalOutstanding) > 0) {
                throw new IllegalArgumentException("Quantity to receive (" + lineRequest.getQuantity()
                        + ") exceeds outstanding (" + totalOutstanding + ") for item: " + sourceLines.getFirst().getItem().getItemCode());
            }

            BigDecimal remaining = lineRequest.getQuantity();
            for (OutletPullOutLine sourceLine : sourceLines) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                BigDecimal outstanding = sourceLine.getQuantity().subtract(sourceLine.getQuantityLoaded());
                if (outstanding.compareTo(BigDecimal.ZERO) <= 0) continue;
                BigDecimal allocated = outstanding.min(remaining);

                PullOutReceiveLine line = new PullOutReceiveLine();
                line.setPullOutReceive(receive);
                line.setItem(sourceLine.getItem());
                line.setOutletPullOutLine(sourceLine);
                line.setQuantity(allocated);
                line.setLineNumber(lineNumber);
                receive.getLines().add(line);

                sourceLine.setQuantityLoaded(sourceLine.getQuantityLoaded().add(allocated));
                remaining = remaining.subtract(allocated);
            }
            lineNumber++;
        }

        outletPullOut.setLoaded(isFullyLoaded(outletPullOut));

        // Generated last, only once validation has fully passed, to avoid burning
        // reference numbers on requests that were always going to be rejected.
        receive.setReferenceNumber(transactionReferenceService.next(company.getId(), TRANSACTION_TYPE, REFERENCE_PREFIX));

        PullOutReceive saved = pullOutReceiveRepository.save(receive);
        transactionEventService.recordSystemEvent(company, TransactionType.PULL_OUT_RECEIVE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        for (PullOutReceiveLine line : saved.getLines()) {
            postMovement(TRANSACTION_TYPE, saved, line.getItem(), line.getQuantity(), now, username);
        }

        outletPullOutRepository.save(outletPullOut);

        log.info("User '{}' posted pull out receive (id={}) with {} line(s) into main warehouse {} (against pull out {}, now {})",
                username, saved.getId(), saved.getLines().size(), saved.getWarehouse().getId(),
                outletPullOut.getReferenceNumber(), outletPullOut.isLoaded() ? "fully received" : "partially received");
        return saved;
    }

    private OutletPullOut loadAndValidateOutletPullOut(Long outletPullOutId, Company company) {
        OutletPullOut outletPullOut = outletPullOutRepository.findById(outletPullOutId)
                .orElseThrow(() -> new IllegalArgumentException("Outlet pull out not found: " + outletPullOutId));
        if (!outletPullOut.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Outlet pull out does not belong to company: " + company.getId());
        }
        if (outletPullOut.isVoided()) {
            throw new IllegalArgumentException("Cannot receive against a voided outlet pull out: " + outletPullOutId);
        }
        if (outletPullOut.isLoaded()) {
            throw new IllegalArgumentException("Outlet pull out already fully received: " + outletPullOutId);
        }
        return outletPullOut;
    }

    private boolean isFullyLoaded(OutletPullOut outletPullOut) {
        return outletPullOut.getLines().stream()
                .allMatch(l -> l.getQuantityLoaded().compareTo(l.getQuantity()) >= 0);
    }

    // Voiding is the only sanctioned way to cancel an immutable transaction (docs/TRANSACTIONS.md "Voiding").
    // Nothing loads from a Pull Out Receive, so it's always voidable; it gives the amount back to transit
    // and un-loads the Outlet Pull Out by the voided amounts.
    @Transactional
    public PullOutReceive voidPullOutReceive(Long id, String username) {
        PullOutReceive receive = findById(id, username);

        if (receive.isVoided()) {
            throw new IllegalArgumentException("Pull out receive already voided: " + id);
        }

        // Voiding takes the received stock back out of on-hand, which may since have been sold.
        inventoryStockService.assertAvailable(receive.getWarehouse(), receive.getLines(), PullOutReceiveLine::getItem,
                PullOutReceiveLine::getQuantity);

        Instant now = Instant.now();

        for (PullOutReceiveLine line : receive.getLines()) {
            postMovement(VOID_SOURCE_TYPE, receive, line.getItem(), line.getQuantity().negate(), now, username);

            OutletPullOutLine sourceLine = line.getOutletPullOutLine();
            sourceLine.setQuantityLoaded(sourceLine.getQuantityLoaded().subtract(line.getQuantity()));
        }

        OutletPullOut outletPullOut = receive.getOutletPullOut();
        outletPullOut.setLoaded(isFullyLoaded(outletPullOut));
        outletPullOutRepository.save(outletPullOut);

        receive.setVoided(true);
        receive.setVoidedAt(now);
        receive.setVoidedBy(username);

        PullOutReceive saved = pullOutReceiveRepository.save(receive);
        transactionEventService.recordSystemEvent(saved.getCompany(), TransactionType.PULL_OUT_RECEIVE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided pull out receive (id={})", username, id);
        return saved;
    }

    // Both sides of the main warehouse's ledger at once: on-hand up, in-transit down (mirrors Outlet Receive).
    private void postMovement(String sourceType, PullOutReceive receive, Item item, BigDecimal quantity, Instant now, String username) {
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

        inventoryStockService.apply(item, warehouse, quantity, quantity.negate(), now);
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
