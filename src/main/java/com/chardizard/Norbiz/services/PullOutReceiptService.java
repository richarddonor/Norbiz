package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.PullOutReceiptLineRequest;
import com.chardizard.Norbiz.dto.PullOutReceiptRequest;
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

// Receives an Outlet Pull-out's in-transit quantity into the main warehouse's on-hand stock
// (docs/TRANSACTIONS.md "Pull-out Receipt") — the reverse of Outlet Receipt. Supports partial receiving.
@Service
@RequiredArgsConstructor
public class PullOutReceiptService {

    private static final Logger log = LoggerFactory.getLogger(PullOutReceiptService.class);
    private static final String TRANSACTION_TYPE = TransactionType.PULL_OUT_RECEIPT.name();
    private static final String VOID_SOURCE_TYPE = "PULL_OUT_RECEIPT_VOID";
    private static final String REFERENCE_PREFIX = "OPO";

    private final PullOutReceiptRepository pullOutReceiptRepository;
    private final OutletPullOutRepository outletPullOutRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final InventoryStockService inventoryStockService;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;

    public Page<PullOutReceipt> findAllForUser(String username, Long customerId, Long outletPullOutId, Map<String, String> filters,
                                               Instant dateFrom, Instant dateTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<PullOutReceipt> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<PullOutReceipt> customerScope = customerId == null ? null
                : (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId);
        Specification<PullOutReceipt> outletPullOutScope = outletPullOutId == null ? null
                : (root, query, cb) -> cb.equal(root.get("outletPullOut").get("id"), outletPullOutId);

        Specification<PullOutReceipt> spec = SpecificationUtils.allOf(
                companyScope,
                customerScope,
                outletPullOutScope,
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.dateRange("receiptDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        );

        return pullOutReceiptRepository.findAll(spec, pageable);
    }

    public PullOutReceipt findById(Long id, String username) {
        PullOutReceipt receipt = pullOutReceiptRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Pull-out receipt not found: " + id));
        assertCompanyAccess(username, receipt.getCompany().getId());
        return receipt;
    }

    @Transactional
    public PullOutReceipt create(PullOutReceiptRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        OutletPullOut outletPullOut = loadAndValidateOutletPullOut(request.getOutletPullOutId(), company);

        Instant receiptDate = DateRangeUtils.startOfDayUtc(request.getReceiptDate());
        if (receiptDate == null) {
            throw new IllegalArgumentException("receiptDate is required (yyyy-MM-dd)");
        }

        Instant now = Instant.now();

        PullOutReceipt receipt = new PullOutReceipt();
        receipt.setCompany(company);
        receipt.setOutletPullOut(outletPullOut);
        receipt.setCustomer(outletPullOut.getCustomer());
        receipt.setWarehouse(outletPullOut.getDestinationWarehouse());
        receipt.setReceiptDate(receiptDate);
        receipt.setRemarks(request.getRemarks());
        receipt.setSheetNumber(request.getSheetNumber());
        receipt.setCreatedAt(now);
        receipt.setCreatedBy(username);

        // Same allocation as OutletReceiptService: a pull-out may carry more than one line for the same item,
        // so the requested quantity is spread across that item's lines oldest-first (by id), splitting into
        // several PullOutReceiptLine rows (sharing the request's lineNumber) when it spans more than one.
        Map<Long, List<OutletPullOutLine>> linesByItemId = outletPullOut.getLines().stream()
                .sorted(Comparator.comparing(OutletPullOutLine::getId))
                .collect(Collectors.groupingBy(l -> l.getItem().getId()));

        int lineNumber = 1;
        for (PullOutReceiptLineRequest lineRequest : request.getLines()) {
            List<OutletPullOutLine> sourceLines = linesByItemId.get(lineRequest.getItemId());
            if (sourceLines == null) {
                throw new IllegalArgumentException("Item " + lineRequest.getItemId() + " is not on outlet pull-out: " + request.getOutletPullOutId());
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

                PullOutReceiptLine line = new PullOutReceiptLine();
                line.setPullOutReceipt(receipt);
                line.setItem(sourceLine.getItem());
                line.setOutletPullOutLine(sourceLine);
                line.setQuantity(allocated);
                line.setLineNumber(lineNumber);
                receipt.getLines().add(line);

                sourceLine.setQuantityLoaded(sourceLine.getQuantityLoaded().add(allocated));
                remaining = remaining.subtract(allocated);
            }
            lineNumber++;
        }

        outletPullOut.setLoaded(isFullyLoaded(outletPullOut));

        // Generated last, only once validation has fully passed, to avoid burning
        // reference numbers on requests that were always going to be rejected.
        receipt.setReferenceNumber(transactionReferenceService.next(company.getId(), TRANSACTION_TYPE, REFERENCE_PREFIX));

        PullOutReceipt saved = pullOutReceiptRepository.save(receipt);
        transactionEventService.recordSystemEvent(company, TransactionType.PULL_OUT_RECEIPT, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        for (PullOutReceiptLine line : saved.getLines()) {
            postMovement(TRANSACTION_TYPE, saved, line.getItem(), line.getQuantity(), now, username);
        }

        outletPullOutRepository.save(outletPullOut);

        log.info("User '{}' posted pull-out receipt (id={}) with {} line(s) into main warehouse {} (against pull-out {}, now {})",
                username, saved.getId(), saved.getLines().size(), saved.getWarehouse().getId(),
                outletPullOut.getReferenceNumber(), outletPullOut.isLoaded() ? "fully received" : "partially received");
        return saved;
    }

    private OutletPullOut loadAndValidateOutletPullOut(Long outletPullOutId, Company company) {
        OutletPullOut outletPullOut = outletPullOutRepository.findById(outletPullOutId)
                .orElseThrow(() -> new IllegalArgumentException("Outlet pull-out not found: " + outletPullOutId));
        if (!outletPullOut.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Outlet pull-out does not belong to company: " + company.getId());
        }
        if (outletPullOut.isVoided()) {
            throw new IllegalArgumentException("Cannot receive against a voided outlet pull-out: " + outletPullOutId);
        }
        if (outletPullOut.isLoaded()) {
            throw new IllegalArgumentException("Outlet pull-out already fully received: " + outletPullOutId);
        }
        return outletPullOut;
    }

    private boolean isFullyLoaded(OutletPullOut outletPullOut) {
        return outletPullOut.getLines().stream()
                .allMatch(l -> l.getQuantityLoaded().compareTo(l.getQuantity()) >= 0);
    }

    // Voiding is the only sanctioned way to cancel an immutable transaction (docs/TRANSACTIONS.md "Voiding").
    // Nothing loads from a Pull-out Receipt, so it's always voidable; it gives the amount back to transit
    // and un-loads the Outlet Pull-out by the voided amounts.
    @Transactional
    public PullOutReceipt voidPullOutReceipt(Long id, String username) {
        PullOutReceipt receipt = findById(id, username);

        if (receipt.isVoided()) {
            throw new IllegalArgumentException("Pull-out receipt already voided: " + id);
        }

        // Voiding takes the received stock back out of on-hand, which may since have been sold.
        inventoryStockService.assertAvailable(receipt.getWarehouse(), receipt.getLines(), PullOutReceiptLine::getItem,
                PullOutReceiptLine::getQuantity);

        Instant now = Instant.now();

        for (PullOutReceiptLine line : receipt.getLines()) {
            postMovement(VOID_SOURCE_TYPE, receipt, line.getItem(), line.getQuantity().negate(), now, username);

            OutletPullOutLine sourceLine = line.getOutletPullOutLine();
            sourceLine.setQuantityLoaded(sourceLine.getQuantityLoaded().subtract(line.getQuantity()));
        }

        OutletPullOut outletPullOut = receipt.getOutletPullOut();
        outletPullOut.setLoaded(isFullyLoaded(outletPullOut));
        outletPullOutRepository.save(outletPullOut);

        receipt.setVoided(true);
        receipt.setVoidedAt(now);
        receipt.setVoidedBy(username);

        PullOutReceipt saved = pullOutReceiptRepository.save(receipt);
        transactionEventService.recordSystemEvent(saved.getCompany(), TransactionType.PULL_OUT_RECEIPT, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided pull-out receipt (id={})", username, id);
        return saved;
    }

    // Both sides of the main warehouse's ledger at once: on-hand up, in-transit down (mirrors Outlet Receipt).
    private void postMovement(String sourceType, PullOutReceipt receipt, Item item, BigDecimal quantity, Instant now, String username) {
        Warehouse warehouse = receipt.getWarehouse();

        InventoryMovement movement = new InventoryMovement();
        movement.setCompany(receipt.getCompany());
        movement.setItem(item);
        movement.setWarehouse(warehouse);
        movement.setQuantityDelta(quantity);
        movement.setTransitQuantityDelta(quantity.negate());
        movement.setMovementDate(receipt.getReceiptDate());
        movement.setSourceType(sourceType);
        movement.setSourceId(receipt.getId());
        movement.setReferenceNumber(receipt.getReferenceNumber());
        movement.setSheetNumber(receipt.getSheetNumber());
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
