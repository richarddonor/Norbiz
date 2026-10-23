package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.SalesInvoiceReturnLineRequest;
import com.chardizard.Norbiz.dto.SalesInvoiceReturnRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import com.chardizard.Norbiz.security.CompanyAccess;
import com.chardizard.Norbiz.util.AccountingAmounts;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sales Invoice Return (docs/ACCOUNTING.md "Sales Invoice Return"): a customer sends back items billed on one or more
 * of its Sales Invoices. The stock comes back into the company's main warehouse, and the customer is credited with
 * what those items were billed at (each invoice line's net unit price) — a RECEIVABLE CREDIT that, by default, is
 * applied straight to the returned invoices' open balances. Each invoice line tracks how much was returned
 * ({@code quantityLoaded}); an invoice is {@code loaded} once everything on it came back.
 */
@Service
@RequiredArgsConstructor
public class SalesInvoiceReturnService {

    private static final Logger log = LoggerFactory.getLogger(SalesInvoiceReturnService.class);
    private static final TransactionType TYPE = TransactionType.SALES_INVOICE_RETURN;
    private static final String SOURCE_TYPE = TYPE.name();
    private static final String VOID_SOURCE_TYPE = "SALES_INVOICE_RETURN_VOID";
    private static final String REFERENCE_PREFIX = "SIR";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final SalesInvoiceReturnRepository returnRepository;
    private final SalesInvoiceLineRepository salesInvoiceLineRepository;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final InventoryStockService inventoryStockService;
    private final CompanyRepository companyRepository;
    private final CustomerRepository customerRepository;
    private final WarehouseRepository warehouseRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;
    private final AccountingSettingsService accountingSettingsService;
    private final OpenItemService openItemService;
    private final CompanyAccess companyAccess;

    public Page<SalesInvoiceReturn> findAllForUser(String username, Long customerId, Long salesInvoiceId, Map<String, String> filters,
                                                   Instant dateFrom, Instant dateTo, Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        Specification<SalesInvoiceReturn> companyScope = companyIds == null ? null
                : (root, query, cb) -> root.get("company").get("id").in(companyIds);
        Specification<SalesInvoiceReturn> customerScope = customerId == null ? null
                : (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId);
        // Returns with at least one line against the given invoice.
        Specification<SalesInvoiceReturn> invoiceScope = salesInvoiceId == null ? null : (root, query, cb) -> {
            var sub = query.subquery(Long.class);
            var line = sub.from(SalesInvoiceReturnLine.class);
            sub.select(line.get("id")).where(
                    cb.equal(line.get("salesInvoiceReturn"), root),
                    cb.equal(line.get("salesInvoiceLine").get("salesInvoice").get("id"), salesInvoiceId));
            return cb.exists(sub);
        };

        return returnRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                customerScope,
                invoiceScope,
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.containsIgnoreCase("customer.name", filters.get("customer")),
                SpecificationUtils.containsIgnoreCase("remarks", filters.get("remarks")),
                SpecificationUtils.dateRange("returnDate", dateFrom, dateTo),
                SpecificationUtils.booleanEquals("voided", filters.get("voided")),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        ), pageable);
    }

    public SalesInvoiceReturn findById(Long id, String username) {
        SalesInvoiceReturn ret = returnRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Sales invoice return not found: " + id));
        companyAccess.assertCompanyAccess(username, ret.getCompany().getId());
        return ret;
    }

    @Transactional
    public SalesInvoiceReturn create(SalesInvoiceReturnRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));
        companyAccess.assertCompanyAccess(username, company.getId());

        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + request.getCustomerId()));
        if (!customer.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Customer does not belong to company: " + company.getId());
        }

        Instant returnDate = DateRangeUtils.startOfDayUtc(request.getReturnDate());
        accountingSettingsService.assertOnOrAfterStart(company, returnDate, "Return");

        Warehouse mainWarehouse = warehouseRepository.findFirstByCompanyIdAndMainTrue(company.getId())
                .orElseThrow(() -> new IllegalArgumentException("No main warehouse configured for company: " + company.getId()));
        if (!mainWarehouse.isActive()) {
            throw new IllegalArgumentException("Main warehouse is inactive: " + mainWarehouse.getName());
        }

        Instant now = Instant.now();
        SalesInvoiceReturn ret = new SalesInvoiceReturn();
        ret.setCompany(company);
        ret.setCustomer(customer);
        ret.setWarehouse(mainWarehouse);
        ret.setReturnDate(returnDate);
        ret.setRemarks(request.getRemarks());
        ret.setSheetNumber(request.getSheetNumber());
        ret.setCreatedAt(now);
        ret.setCreatedBy(username);

        // Unrounded credit per returned invoice, in first-returned order; rounded once into the return's net amount.
        Map<SalesInvoice, BigDecimal> creditByInvoice = new LinkedHashMap<>();
        int lineNumber = 1;
        for (SalesInvoiceReturnLineRequest lineRequest : request.getLines()) {
            SalesInvoiceLine invoiceLine = salesInvoiceLineRepository.findById(lineRequest.getSalesInvoiceLineId())
                    .orElseThrow(() -> new IllegalArgumentException("Sales invoice line not found: " + lineRequest.getSalesInvoiceLineId()));
            SalesInvoice invoice = invoiceLine.getSalesInvoice();
            if (!invoice.getCompany().getId().equals(company.getId())) {
                throw new IllegalArgumentException("Sales invoice does not belong to company: " + company.getId());
            }
            if (!invoice.getCustomer().getId().equals(customer.getId())) {
                throw new IllegalArgumentException("Sales invoice " + invoice.getReferenceNumber() + " belongs to another customer");
            }
            if (invoice.isVoided()) {
                throw new IllegalArgumentException("Cannot return against a voided sales invoice: " + invoice.getReferenceNumber());
            }
            if (returnDate.isBefore(invoice.getInvoiceDate())) {
                throw new IllegalArgumentException("returnDate cannot be before the date of sales invoice " + invoice.getReferenceNumber());
            }
            // Outstanding is re-read after earlier request lines loaded the same invoice line.
            BigDecimal outstanding = invoiceLine.getQuantity().subtract(invoiceLine.getQuantityLoaded());
            if (lineRequest.getQuantity().compareTo(outstanding) > 0) {
                throw new IllegalArgumentException("Quantity to return (" + lineRequest.getQuantity().stripTrailingZeros().toPlainString()
                        + ") exceeds what is still unreturned (" + outstanding.stripTrailingZeros().toPlainString() + ") of "
                        + invoiceLine.getItem().getItemCode() + " on " + invoice.getReferenceNumber());
            }

            BigDecimal netUnitPrice = netUnitPrice(invoiceLine);
            SalesInvoiceReturnLine line = new SalesInvoiceReturnLine();
            line.setSalesInvoiceReturn(ret);
            line.setItem(invoiceLine.getItem());
            line.setSalesInvoiceLine(invoiceLine);
            line.setQuantity(lineRequest.getQuantity());
            line.setUnitPrice(netUnitPrice.setScale(4, RoundingMode.HALF_UP));
            line.setLineNumber(lineNumber++);
            line.setPostsStock(true);
            ret.getLines().add(line);

            invoiceLine.setQuantityLoaded(invoiceLine.getQuantityLoaded().add(lineRequest.getQuantity()));
            creditByInvoice.merge(invoice, lineRequest.getQuantity().multiply(netUnitPrice), BigDecimal::add);
        }
        for (SalesInvoice invoice : creditByInvoice.keySet()) {
            invoice.setLoaded(isFullyReturned(invoice));
        }
        ret.setNetAmount(AccountingAmounts.money(creditByInvoice.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)));

        // Generated last, only once validation has fully passed, to avoid burning
        // reference numbers on requests that were always going to be rejected.
        ret.setReferenceNumber(transactionReferenceService.next(company.getId(), TYPE.name(), REFERENCE_PREFIX));

        SalesInvoiceReturn saved = returnRepository.save(ret);
        transactionEventService.recordSystemEvent(company, TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        for (SalesInvoiceReturnLine line : saved.getLines()) {
            postMovement(SOURCE_TYPE, saved, line, line.getQuantity(), now, username);
        }
        salesInvoiceRepository.saveAll(creditByInvoice.keySet());

        int applied = 0;
        if (AccountingAmounts.isPositive(saved.getNetAmount())) {
            OpenItem credit = openItemService.open(company, AccountSide.RECEIVABLE, customer, null, OpenItemDirection.CREDIT,
                    OpenItemSourceType.SALES_INVOICE_RETURN, saved.getId(), saved.getReferenceNumber(), returnDate, null,
                    saved.getNetAmount(), true, username);
            saved.setOpenItem(credit);
            if (!Boolean.FALSE.equals(request.getApplyToInvoices())) {
                applied = applyToInvoices(saved, credit, creditByInvoice, username);
            }
        }

        log.info("User '{}' posted sales invoice return (id={}) {} for {} with {} line(s) from customer {} against {} invoice(s), "
                        + "{} application(s)", username, saved.getId(), saved.getReferenceNumber(), saved.getNetAmount().toPlainString(),
                saved.getLines().size(), customer.getId(), creditByInvoice.size(), applied);
        return saved;
    }

    // Each returned invoice gets what its returned items were billed at, capped by its open balance and by what is left
    // of the credit (rounding). Invoices with nothing open (paid, zero-value) are skipped; that part stays unapplied.
    private int applyToInvoices(SalesInvoiceReturn ret, OpenItem credit, Map<SalesInvoice, BigDecimal> creditByInvoice, String username) {
        List<OpenItem> charges = new ArrayList<>();
        Map<OpenItem, BigDecimal> wanted = new LinkedHashMap<>();
        for (Map.Entry<SalesInvoice, BigDecimal> e : creditByInvoice.entrySet()) {
            OpenItem charge = e.getKey().getOpenItem();
            if (charge == null) continue;
            charges.add(charge);
            wanted.put(charge, AccountingAmounts.money(e.getValue()));
        }
        List<OpenItem> toLock = new ArrayList<>(charges);
        toLock.add(credit);
        openItemService.lock(toLock);

        int applied = 0;
        for (Map.Entry<OpenItem, BigDecimal> e : wanted.entrySet()) {
            OpenItem charge = e.getKey();
            if (charge.getStatus() != OpenItemStatus.OPEN) continue;
            BigDecimal amount = e.getValue().min(charge.getBalance()).min(credit.getBalance());
            if (!AccountingAmounts.isPositive(amount)) continue;
            openItemService.allocate(credit, charge, amount, ret.getReturnDate(), TYPE, ret.getId(), username);
            applied++;
        }
        return applied;
    }

    // Voiding is the only sanctioned way to cancel an immutable transaction (docs/TRANSACTIONS.md "Voiding").
    // Takes the stock back out of the main warehouse, reverses the credit's own applications and reopens the invoice
    // lines. Blocked while a later payment or refund used the credit (void that first).
    @Transactional
    public SalesInvoiceReturn voidReturn(Long id, String username) {
        SalesInvoiceReturn ret = findById(id, username);
        if (ret.isVoided()) {
            throw new IllegalArgumentException("Sales invoice return already voided: " + id);
        }
        OpenItem credit = ret.getOpenItem();
        if (credit != null && openItemService.appliedByOthers(credit, TYPE, ret.getId())) {
            throw new IllegalArgumentException("Cannot void " + ret.getReferenceNumber()
                    + ": its credit was used by later payments/refunds — void those first");
        }
        List<SalesInvoiceReturnLine> stockLines = ret.getLines().stream().filter(SalesInvoiceReturnLine::isPostsStock).toList();
        // Voiding takes the returned stock back out of on-hand, which may since have been sold again.
        inventoryStockService.assertAvailable(ret.getWarehouse(), stockLines, SalesInvoiceReturnLine::getItem, SalesInvoiceReturnLine::getQuantity);

        Instant now = Instant.now();
        if (credit != null) {
            openItemService.reverseBySource(TYPE, ret.getId(), AllocationReversalReason.VOIDED, ret.getReturnDate(), username);
            openItemService.voidItem(credit, username);
        }
        for (SalesInvoiceReturnLine line : stockLines) {
            postMovement(VOID_SOURCE_TYPE, ret, line, line.getQuantity().negate(), now, username);
        }
        Set<SalesInvoice> invoices = new LinkedHashSet<>();
        for (SalesInvoiceReturnLine line : ret.getLines()) {
            SalesInvoiceLine invoiceLine = line.getSalesInvoiceLine();
            if (invoiceLine == null) continue;
            // Migrated over-returns were capped at the sold quantity, so never go below zero.
            invoiceLine.setQuantityLoaded(invoiceLine.getQuantityLoaded().subtract(line.getQuantity()).max(BigDecimal.ZERO));
            invoices.add(invoiceLine.getSalesInvoice());
        }
        for (SalesInvoice invoice : invoices) {
            invoice.setLoaded(isFullyReturned(invoice));
        }
        salesInvoiceRepository.saveAll(invoices);

        ret.setVoided(true);
        ret.setVoidedAt(now);
        ret.setVoidedBy(username);
        SalesInvoiceReturn saved = returnRepository.saveAndFlush(ret);
        transactionEventService.recordSystemEvent(saved.getCompany(), TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided sales invoice return (id={})", username, id);
        return saved;
    }

    /** What one unit on the invoice line was billed at: unit price less the line and the invoice discounts, unrounded. */
    public static BigDecimal netUnitPrice(SalesInvoiceLine line) {
        BigDecimal afterLine = AccountingAmounts.lineNet(BigDecimal.ONE, line.getUnitPrice(), line.getDiscountPercentage());
        BigDecimal headerDiscount = line.getSalesInvoice().getDiscountPercentage();
        return afterLine.subtract(afterLine.multiply(headerDiscount).divide(HUNDRED));
    }

    private static boolean isFullyReturned(SalesInvoice invoice) {
        return invoice.getLines().stream().allMatch(l -> l.getQuantityLoaded().compareTo(l.getQuantity()) >= 0);
    }

    // On-hand only, in the main warehouse: positive on return, negative on void.
    private void postMovement(String sourceType, SalesInvoiceReturn ret, SalesInvoiceReturnLine line, BigDecimal quantityDelta,
                              Instant now, String username) {
        InventoryMovement movement = new InventoryMovement();
        movement.setCompany(ret.getCompany());
        movement.setItem(line.getItem());
        movement.setWarehouse(ret.getWarehouse());
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

        inventoryStockService.apply(line.getItem(), ret.getWarehouse(), quantityDelta, BigDecimal.ZERO, now);
    }
}
