package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.SalesInvoiceLineRequest;
import com.chardizard.Norbiz.dto.SalesInvoiceRequest;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sales Invoice (docs/ACCOUNTING.md "Sales Invoice"): bills a customer and opens a RECEIVABLE CHARGE for its net
 * amount (none for a zero-value invoice, which is PAID at once). Direct mode carries its own lines and deducts the company's main warehouse stock, like a Delivery
 * Receipt; DR mode bills one plain-customer Delivery Receipt in full (lines copied, no stock effect).
 */
@Service
@RequiredArgsConstructor
public class SalesInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(SalesInvoiceService.class);
    private static final String TRANSACTION_TYPE = TransactionType.SALES_INVOICE.name();
    private static final String VOID_SOURCE_TYPE = "SALES_INVOICE_VOID";
    private static final String REFERENCE_PREFIX = "SI";

    private final SalesInvoiceRepository salesInvoiceRepository;
    private final DeliveryReceiptRepository deliveryReceiptRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final InventoryStockService inventoryStockService;
    private final CompanyRepository companyRepository;
    private final CustomerRepository customerRepository;
    private final WarehouseRepository warehouseRepository;
    private final ItemRepository itemRepository;
    private final ItemPriceRepository itemPriceRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;
    private final AccountingSettingsService accountingSettingsService;
    private final OpenItemService openItemService;
    private final CompanyAccess companyAccess;

    public Page<SalesInvoice> findAllForUser(String username, Long customerId, PaymentStatus paymentStatus, Map<String, String> filters,
                                             Instant dateFrom, Instant dateTo, Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        Specification<SalesInvoice> companyScope = companyIds == null ? null : (root, query, cb) -> root.get("company").get("id").in(companyIds);
        Specification<SalesInvoice> customerScope = customerId == null ? null
                : (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId);
        Specification<SalesInvoice> paymentStatusScope = paymentStatus == null ? null
                : (root, query, cb) -> cb.equal(root.get("paymentStatus"), paymentStatus);

        return salesInvoiceRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                customerScope,
                paymentStatusScope,
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.containsIgnoreCase("customer.name", filters.get("customer")),
                SpecificationUtils.containsIgnoreCase("deliveryReceipt.referenceNumber", filters.get("deliveryReceipt")),
                SpecificationUtils.dateRange("invoiceDate", dateFrom, dateTo),
                SpecificationUtils.booleanEquals("voided", filters.get("voided")),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        ), pageable);
    }

    public SalesInvoice findById(Long id, String username) {
        SalesInvoice invoice = salesInvoiceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Sales invoice not found: " + id));
        companyAccess.assertCompanyAccess(username, invoice.getCompany().getId());
        return invoice;
    }

    @Transactional
    public SalesInvoice create(SalesInvoiceRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));
        companyAccess.assertCompanyAccess(username, company.getId());

        Instant invoiceDate = DateRangeUtils.startOfDayUtc(request.getInvoiceDate());
        accountingSettingsService.assertOnOrAfterStart(company, invoiceDate, "Invoice");

        DeliveryReceipt deliveryReceipt = request.getDeliveryReceiptId() == null ? null
                : loadAndValidateDeliveryReceipt(request.getDeliveryReceiptId(), company, invoiceDate);
        Customer customer;
        if (deliveryReceipt != null) {
            customer = deliveryReceipt.getCustomer();
            if (request.getCustomerId() != null && !request.getCustomerId().equals(customer.getId())) {
                throw new IllegalArgumentException("customerId does not match the delivery receipt's customer");
            }
        } else {
            if (request.getCustomerId() == null) {
                throw new IllegalArgumentException("customerId is required when deliveryReceiptId is not supplied (Direct mode)");
            }
            customer = customerRepository.findById(request.getCustomerId())
                    .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + request.getCustomerId()));
            if (!customer.getCompany().getId().equals(company.getId())) {
                throw new IllegalArgumentException("Customer does not belong to company: " + company.getId());
            }
            if (!customer.isActive()) {
                throw new IllegalArgumentException("Customer is inactive: " + customer.getName());
            }
        }

        Instant now = Instant.now();
        SalesInvoice invoice = new SalesInvoice();
        invoice.setCompany(company);
        invoice.setCustomer(customer);
        invoice.setDeliveryReceipt(deliveryReceipt);
        invoice.setInvoiceDate(invoiceDate);
        invoice.setRemarks(request.getRemarks());
        invoice.setSheetNumber(request.getSheetNumber());
        invoice.setDiscountPercentage(request.getDiscountPercentage() != null ? request.getDiscountPercentage() : BigDecimal.ZERO);
        invoice.setPaymentStatus(PaymentStatus.UNPAID);
        invoice.setCreatedAt(now);
        invoice.setCreatedBy(username);

        if (deliveryReceipt != null) {
            invoice.setWarehouse(deliveryReceipt.getWarehouse());
            Map<Long, BigDecimal> discountByItemId = new HashMap<>();
            if (request.getLines() != null) {
                for (SalesInvoiceLineRequest lineRequest : request.getLines()) {
                    if (lineRequest.getDiscountPercentage() != null) discountByItemId.put(lineRequest.getItemId(), lineRequest.getDiscountPercentage());
                }
            }
            // Billed in full, 1:1 (like a PO-based Purchase Invoice): the DR's lines are copied verbatim.
            for (DeliveryReceiptLine drLine : deliveryReceipt.getLines()) {
                SalesInvoiceLine line = new SalesInvoiceLine();
                line.setSalesInvoice(invoice);
                line.setItem(drLine.getItem());
                line.setDeliveryReceiptLine(drLine);
                line.setQuantity(drLine.getQuantity());
                line.setUnitPrice(drLine.getUnitPrice());
                line.setDiscountPercentage(discountByItemId.getOrDefault(drLine.getItem().getId(), BigDecimal.ZERO));
                line.setLineNumber(drLine.getLineNumber());
                invoice.getLines().add(line);
            }
            deliveryReceipt.setBilled(true);
        } else {
            Warehouse mainWarehouse = warehouseRepository.findFirstByCompanyIdAndMainTrue(company.getId())
                    .orElseThrow(() -> new IllegalArgumentException("No main warehouse configured for company: " + company.getId()));
            if (!mainWarehouse.isActive()) {
                throw new IllegalArgumentException("Main warehouse is inactive: " + mainWarehouse.getName());
            }
            invoice.setWarehouse(mainWarehouse);
            if (request.getLines() == null || request.getLines().isEmpty()) {
                throw new IllegalArgumentException("lines are required when deliveryReceiptId is not supplied (Direct mode)");
            }
            int lineNumber = 1;
            for (SalesInvoiceLineRequest lineRequest : request.getLines()) {
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
                if (lineRequest.getQuantity() == null || lineRequest.getQuantity().signum() <= 0) {
                    throw new IllegalArgumentException("Line quantity must be greater than zero for item: " + item.getItemCode());
                }
                SalesInvoiceLine line = new SalesInvoiceLine();
                line.setSalesInvoice(invoice);
                line.setItem(item);
                line.setQuantity(lineRequest.getQuantity());
                line.setUnitPrice(lineRequest.getUnitPrice() != null ? lineRequest.getUnitPrice() : currentUnitPrice(item));
                line.setDiscountPercentage(lineRequest.getDiscountPercentage() != null ? lineRequest.getDiscountPercentage() : BigDecimal.ZERO);
                line.setLineNumber(lineNumber++);
                invoice.getLines().add(line);
            }
            // On-hand in the main warehouse can't go below zero (docs/INVENTORY.md "Negative stock").
            inventoryStockService.assertAvailable(mainWarehouse, invoice.getLines(), SalesInvoiceLine::getItem, SalesInvoiceLine::getQuantity);
        }

        // A zero total is allowed (free goods, testers, samples): it opens no receivable and is PAID from the start.
        invoice.setNetAmount(AccountingAmounts.salesInvoiceNet(invoice));
        if (invoice.getNetAmount().signum() < 0) {
            throw new IllegalArgumentException("Invoice total cannot be negative");
        }
        invoice.setTermDays(request.getTermDays() != null ? request.getTermDays() : customer.getTermDays());
        Instant dueDate = DateRangeUtils.startOfDayUtc(request.getDueDate());
        if (dueDate != null && dueDate.isBefore(invoiceDate)) {
            throw new IllegalArgumentException("dueDate cannot be before invoiceDate");
        }
        invoice.setDueDate(dueDate != null ? dueDate : AccountingAmounts.dueDate(invoiceDate, invoice.getTermDays()));

        // Generated last, only once validation has fully passed, to avoid burning
        // reference numbers on requests that were always going to be rejected.
        invoice.setReferenceNumber(transactionReferenceService.next(company.getId(), TRANSACTION_TYPE, REFERENCE_PREFIX));

        SalesInvoice saved = salesInvoiceRepository.save(invoice);
        transactionEventService.recordSystemEvent(company, TransactionType.SALES_INVOICE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        if (deliveryReceipt == null) {
            for (SalesInvoiceLine line : saved.getLines()) {
                postMovement(TRANSACTION_TYPE, saved, line, line.getQuantity().negate(), now, username);
            }
        } else {
            deliveryReceiptRepository.save(deliveryReceipt);
        }

        if (AccountingAmounts.isPositive(saved.getNetAmount())) {
            saved.setOpenItem(openItemService.open(company, AccountSide.RECEIVABLE, customer, null, OpenItemDirection.CHARGE,
                    OpenItemSourceType.SALES_INVOICE, saved.getId(), saved.getReferenceNumber(), invoiceDate, saved.getDueDate(),
                    saved.getNetAmount(), true, username));
        } else {
            saved.setPaymentStatus(PaymentStatus.PAID);
        }

        log.info("User '{}' posted sales invoice (id={}) {} for {} with {} line(s) to customer {}{}", username, saved.getId(),
                saved.getReferenceNumber(), saved.getNetAmount().toPlainString(), saved.getLines().size(), customer.getId(),
                deliveryReceipt != null ? " (billing DR " + deliveryReceipt.getReferenceNumber() + ")" : " (Direct, from main warehouse "
                        + saved.getWarehouse().getId() + ")");
        return saved;
    }

    // Voiding is the only sanctioned way to cancel an immutable transaction (docs/TRANSACTIONS.md "Voiding").
    // Blocked while any payment/credit is applied to it, or anything was returned against it — void those first.
    @Transactional
    public SalesInvoice voidSalesInvoice(Long id, String username) {
        SalesInvoice invoice = findById(id, username);
        if (invoice.isVoided()) {
            throw new IllegalArgumentException("Sales invoice already voided: " + id);
        }
        if (invoice.getLines().stream().anyMatch(l -> l.getQuantityLoaded().signum() > 0)) {
            throw new IllegalArgumentException("Cannot void " + invoice.getReferenceNumber()
                    + ": items were returned against it — void the sales invoice return(s) first");
        }
        if (invoice.getOpenItem() != null) {
            openItemService.voidItem(invoice.getOpenItem(), username);
        }

        Instant now = Instant.now();
        if (invoice.getDeliveryReceipt() == null) {
            for (SalesInvoiceLine line : invoice.getLines()) {
                postMovement(VOID_SOURCE_TYPE, invoice, line, line.getQuantity(), now, username);
            }
        } else {
            DeliveryReceipt deliveryReceipt = invoice.getDeliveryReceipt();
            deliveryReceipt.setBilled(false);
            deliveryReceiptRepository.save(deliveryReceipt);
        }

        invoice.setVoided(true);
        invoice.setVoidedAt(now);
        invoice.setVoidedBy(username);
        // Flushed now: the partial unique index on delivery_receipt_id (WHERE NOT voided) must see it voided before the
        // same transaction bills the DR again (Hibernate would otherwise flush that insert first).
        SalesInvoice saved = salesInvoiceRepository.saveAndFlush(invoice);
        transactionEventService.recordSystemEvent(saved.getCompany(), TransactionType.SALES_INVOICE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided sales invoice (id={})", username, id);
        return saved;
    }

    private DeliveryReceipt loadAndValidateDeliveryReceipt(Long deliveryReceiptId, Company company, Instant invoiceDate) {
        DeliveryReceipt receipt = deliveryReceiptRepository.findById(deliveryReceiptId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery receipt not found: " + deliveryReceiptId));
        if (!receipt.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Delivery receipt does not belong to company: " + company.getId());
        }
        if (receipt.isVoided()) {
            throw new IllegalArgumentException("Cannot bill a voided delivery receipt: " + receipt.getReferenceNumber());
        }
        if (receipt.isBilled()) {
            throw new IllegalArgumentException("Delivery receipt already billed: " + receipt.getReferenceNumber());
        }
        // Outlet deliveries are consignment stock moves — what an outlet owes is billed when it sells (memos), not on delivery.
        if (receipt.getCustomer().getType() != CustomerType.CUSTOMER) {
            throw new IllegalArgumentException("Only deliveries to a plain customer can be billed; " + receipt.getReferenceNumber()
                    + " went to an outlet");
        }
        if (invoiceDate.isBefore(receipt.getDeliveryDate())) {
            throw new IllegalArgumentException("invoiceDate cannot be before the delivery receipt's date");
        }
        return receipt;
    }

    private BigDecimal currentUnitPrice(Item item) {
        return itemPriceRepository.findByItemIdAndPriceType(item.getId(), PriceType.UNIT_PRICE)
                .map(ItemPrice::getAmount)
                .orElse(BigDecimal.ZERO);
    }

    // Direct mode only: the main warehouse loses (void: regains) on-hand stock, same as a Delivery Receipt.
    private void postMovement(String sourceType, SalesInvoice invoice, SalesInvoiceLine line, BigDecimal quantityDelta,
                              Instant now, String username) {
        InventoryMovement movement = new InventoryMovement();
        movement.setCompany(invoice.getCompany());
        movement.setItem(line.getItem());
        movement.setWarehouse(invoice.getWarehouse());
        movement.setQuantityDelta(quantityDelta);
        movement.setTransitQuantityDelta(BigDecimal.ZERO);
        movement.setMovementDate(invoice.getInvoiceDate());
        movement.setSourceType(sourceType);
        movement.setSourceId(invoice.getId());
        movement.setReferenceNumber(invoice.getReferenceNumber());
        movement.setSheetNumber(invoice.getSheetNumber());
        movement.setCreatedAt(now);
        movement.setCreatedBy(username);
        inventoryMovementRepository.save(movement);

        inventoryStockService.apply(line.getItem(), invoice.getWarehouse(), quantityDelta, BigDecimal.ZERO, now);
    }
}
