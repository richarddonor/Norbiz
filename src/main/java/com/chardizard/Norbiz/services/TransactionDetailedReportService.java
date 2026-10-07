package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.DetailedReportFilter;
import com.chardizard.Norbiz.dto.TransactionDetailedReportRow;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import com.chardizard.Norbiz.util.DateRangeUtils;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * "&lt;Transaction&gt; - Detailed" reports: every line item of one transaction type, flattened with its
 * transaction's header, newest transaction first and lines in entry order. Scoped to the caller's
 * companies (SUPER_ADMIN sees all), like the transaction list endpoints.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionDetailedReportService {

    private static final Logger log = LoggerFactory.getLogger(TransactionDetailedReportService.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final UserRepository userRepository;
    private final InventoryAdjustmentLineRepository inventoryAdjustmentLineRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final PurchaseInvoiceLineRepository purchaseInvoiceLineRepository;
    private final PurchaseReceiveLineRepository purchaseReceiveLineRepository;
    private final DeliveryReceiptLineRepository deliveryReceiptLineRepository;
    private final OutletReceiveLineRepository outletReceiveLineRepository;
    private final OutletDeliveryReceiptLineRepository outletDeliveryReceiptLineRepository;
    private final OutletDeliveryReturnLineRepository outletDeliveryReturnLineRepository;
    private final StockTransferLineRepository stockTransferLineRepository;
    private final OutletPullOutLineRepository outletPullOutLineRepository;
    private final PullOutReceiveLineRepository pullOutReceiveLineRepository;
    private final AssemblyLineRepository assemblyLineRepository;

    /**
     * How a line entity reaches its header's fields: the line's header association, and the header's
     * date, notes, counterparty and agent (null when it has none) and source-transaction association names.
     */
    private record Shape(String header, String date, String remarks, String counterparty, String agent, String... sources) {
    }

    public Page<TransactionDetailedReportRow> find(DetailedReportType type, String username, DetailedReportFilter filter,
                                                   boolean canViewCostPrice, Pageable pageable) {
        log.debug("User '{}' requested {} report", username, type.getDisplayName());
        return switch (type) {
            case INVENTORY_ADJUSTMENT -> query(inventoryAdjustmentLineRepository,
                    new Shape("adjustment", "adjustmentDate", "reason", null, null),
                    type, username, filter, pageable, this::fromInventoryAdjustment);
            case PURCHASE_ORDER -> query(purchaseOrderLineRepository,
                    new Shape("purchaseOrder", "orderDate", "remarks", "supplier", null),
                    type, username, filter, pageable, l -> fromPurchaseOrder(l, canViewCostPrice));
            case PURCHASE_INVOICE -> query(purchaseInvoiceLineRepository,
                    new Shape("purchaseInvoice", "invoiceDate", "remarks", "supplier", null, "purchaseOrder"),
                    type, username, filter, pageable, l -> fromPurchaseInvoice(l, canViewCostPrice));
            case PURCHASE_RECEIVE -> query(purchaseReceiveLineRepository,
                    new Shape("purchaseReceive", "receiptDate", "remarks", "supplier", null, "purchaseOrder", "purchaseInvoice"),
                    type, username, filter, pageable, l -> fromPurchaseReceive(l, canViewCostPrice));
            case DELIVERY_RECEIPT -> query(deliveryReceiptLineRepository,
                    new Shape("deliveryReceipt", "deliveryDate", "remarks", "customer", null, "stockTransfer"),
                    type, username, filter, pageable, this::fromDeliveryReceipt);
            case OUTLET_RECEIVE -> query(outletReceiveLineRepository,
                    new Shape("outletReceive", "receiptDate", "remarks", "customer", null, "deliveryReceipt"),
                    type, username, filter, pageable, this::fromOutletReceive);
            case OUTLET_DELIVERY_RECEIPT -> query(outletDeliveryReceiptLineRepository,
                    new Shape("outletDeliveryReceipt", "deliveryDate", "remarks", "customer", "agent"),
                    type, username, filter, pageable, this::fromOutletDeliveryReceipt);
            case OUTLET_DELIVERY_RETURN -> query(outletDeliveryReturnLineRepository,
                    new Shape("outletDeliveryReturn", "returnDate", "remarks", "customer", "agent", "outletDeliveryReceipt"),
                    type, username, filter, pageable, this::fromOutletDeliveryReturn);
            case STOCK_TRANSFER -> query(stockTransferLineRepository,
                    new Shape("stockTransfer", "transferDate", "remarks", "customer", null),
                    type, username, filter, pageable, this::fromStockTransfer);
            case OUTLET_PULL_OUT -> query(outletPullOutLineRepository,
                    new Shape("outletPullOut", "pullOutDate", "remarks", "customer", null),
                    type, username, filter, pageable, this::fromOutletPullOut);
            case PULL_OUT_RECEIVE -> query(pullOutReceiveLineRepository,
                    new Shape("pullOutReceive", "receiptDate", "remarks", "customer", null, "outletPullOut"),
                    type, username, filter, pageable, this::fromPullOutReceive);
            case ASSEMBLY -> query(assemblyLineRepository,
                    new Shape("assembly", "assemblyDate", "remarks", null, null),
                    type, username, filter, pageable, this::fromAssembly);
        };
    }

    private <L> Page<TransactionDetailedReportRow> query(JpaSpecificationExecutor<L> repository, Shape shape,
                                                         DetailedReportType type, String username, DetailedReportFilter f,
                                                         Pageable pageable, Function<L, TransactionDetailedReportRow> mapper) {
        rejectInapplicableFilters(shape, type, f);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
        boolean isSuperAdmin = user.getRoles().stream().anyMatch(r -> r.getName().equals("SUPER_ADMIN"));
        List<Long> companyIds = isSuperAdmin ? null : user.getCompanies().stream().map(Company::getId).toList();
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);

        Instant dateFrom = DateRangeUtils.startOfDayUtc(f.getDateFrom());
        Instant dateTo = DateRangeUtils.endOfDayUtc(f.getDateTo());
        Long counterpartyId = f.getSupplierId() != null ? f.getSupplierId() : f.getCustomerId();

        Specification<L> spec = (root, query, cb) -> {
            From<?, ?> header = root.join(shape.header());
            From<?, ?> item = root.join("item");
            List<Predicate> predicates = new ArrayList<>();
            if (companyIds != null) predicates.add(header.get("company").get("id").in(companyIds));
            if (f.getWarehouseId() != null) predicates.add(cb.equal(header.get("warehouse").get("id"), f.getWarehouseId()));
            if (counterpartyId != null) predicates.add(cb.equal(header.get(shape.counterparty()).get("id"), counterpartyId));
            if (f.getAgentId() != null) predicates.add(cb.equal(header.get(shape.agent()).get("id"), f.getAgentId()));
            if (f.getItemId() != null) predicates.add(cb.equal(item.get("id"), f.getItemId()));
            if (f.getVoided() != null) predicates.add(cb.equal(header.get("voided"), f.getVoided()));
            if (f.getOrigin() != null) predicates.add(cb.equal(header.get("origin"), f.getOrigin()));
            if (dateFrom != null) predicates.add(cb.greaterThanOrEqualTo(header.<Instant>get(shape.date()), dateFrom));
            if (dateTo != null) predicates.add(cb.lessThanOrEqualTo(header.<Instant>get(shape.date()), dateTo));
            addContains(cb, predicates, f.getReferenceNumber(), header.get("referenceNumber"));
            addContains(cb, predicates, f.getSheetNumber(), header.get("sheetNumber"));
            addContains(cb, predicates, f.getRemarks(), header.get(shape.remarks()));
            addContains(cb, predicates, f.getWarehouse(), header.get("warehouse").get("name"));
            addContains(cb, predicates, f.getItemCode(), item.get("itemCode"));
            addContains(cb, predicates, f.getItemName(), item.get("name"));
            if (StringUtils.hasText(f.getCounterparty())) {
                addContains(cb, predicates, f.getCounterparty(), header.get(shape.counterparty()).get("name"));
            }
            if (StringUtils.hasText(f.getSourceReferenceNumber())) {
                // Any source matches — Purchase Receive loads from either a PO or a Direct invoice.
                List<Predicate> any = new ArrayList<>();
                for (String source : shape.sources()) {
                    addContains(cb, any, f.getSourceReferenceNumber(), header.join(source, JoinType.LEFT).get("referenceNumber"));
                }
                predicates.add(cb.or(any.toArray(Predicate[]::new)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        // Fixed, total ordering so export (which pages through every page) never skips or repeats a row.
        Sort sort = Sort.by(
                Sort.Order.desc(shape.header() + "." + shape.date()),
                Sort.Order.desc(shape.header() + ".id"),
                Sort.Order.asc("lineNumber"),
                Sort.Order.asc("id"));
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
        return repository.findAll(spec, sorted).map(mapper);
    }

    private void rejectInapplicableFilters(Shape shape, DetailedReportType type, DetailedReportFilter f) {
        if (f.getSupplierId() != null && !"supplier".equals(shape.counterparty())) {
            throw new IllegalArgumentException("supplierId filter does not apply to " + type.getDisplayName());
        }
        if (f.getCustomerId() != null && !"customer".equals(shape.counterparty())) {
            throw new IllegalArgumentException("customerId filter does not apply to " + type.getDisplayName());
        }
        if (f.getAgentId() != null && shape.agent() == null) {
            throw new IllegalArgumentException("agentId filter does not apply to " + type.getDisplayName());
        }
        if (StringUtils.hasText(f.getCounterparty()) && shape.counterparty() == null) {
            throw new IllegalArgumentException("counterparty filter does not apply to " + type.getDisplayName());
        }
        if (StringUtils.hasText(f.getSourceReferenceNumber()) && shape.sources().length == 0) {
            throw new IllegalArgumentException("sourceReferenceNumber filter does not apply to " + type.getDisplayName());
        }
    }

    private static void addContains(CriteriaBuilder cb, List<Predicate> predicates, String value, Expression<?> path) {
        if (!StringUtils.hasText(value)) return;
        predicates.add(cb.like(cb.lower(path.as(String.class)), "%" + value.trim().toLowerCase() + "%"));
    }

    // ── Row mappers ────────────────────────────────────────────────────────────────────────

    private TransactionDetailedReportRow fromInventoryAdjustment(InventoryAdjustmentLine line) {
        InventoryAdjustment h = line.getAdjustment();
        TransactionDetailedReportRow row = line(line.getId(), line.getLineNumber(), line.getItem(), line.getQuantity(), line.getQuantityLoaded());
        header(row, h.getId(), h.getCompany(), h.getReferenceNumber(), h.getSheetNumber(), h.getAdjustmentDate(), h.getWarehouse(), h.getReason());
        status(row, h.isVoided(), h.getVoidedAt(), h.getVoidedBy(), h.getCreatedAt(), h.getCreatedBy(), h.getOrigin());
        return row;
    }

    private TransactionDetailedReportRow fromPurchaseOrder(PurchaseOrderLine line, boolean canViewCostPrice) {
        PurchaseOrder h = line.getPurchaseOrder();
        TransactionDetailedReportRow row = line(line.getId(), line.getLineNumber(), line.getItem(), line.getQuantity(), line.getQuantityLoaded());
        header(row, h.getId(), h.getCompany(), h.getReferenceNumber(), h.getSheetNumber(), h.getOrderDate(), h.getWarehouse(), h.getRemarks());
        status(row, h.isVoided(), h.getVoidedAt(), h.getVoidedBy(), h.getCreatedAt(), h.getCreatedBy(), h.getOrigin());
        row.setCounterpartyId(h.getSupplier().getId());
        row.setCounterpartyName(h.getSupplier().getName());
        if (canViewCostPrice) price(row, line.getCostPrice(), null);
        return row;
    }

    private TransactionDetailedReportRow fromPurchaseInvoice(PurchaseInvoiceLine line, boolean canViewCostPrice) {
        PurchaseInvoice h = line.getPurchaseInvoice();
        TransactionDetailedReportRow row = line(line.getId(), line.getLineNumber(), line.getItem(), line.getQuantity(), line.getQuantityLoaded());
        header(row, h.getId(), h.getCompany(), h.getReferenceNumber(), h.getSheetNumber(), h.getInvoiceDate(), h.getWarehouse(), h.getRemarks());
        status(row, h.isVoided(), h.getVoidedAt(), h.getVoidedBy(), h.getCreatedAt(), h.getCreatedBy(), h.getOrigin());
        row.setCounterpartyId(h.getSupplier().getId());
        row.setCounterpartyName(h.getSupplier().getName());
        if (h.getPurchaseOrder() != null) row.setSourceReferenceNumber(h.getPurchaseOrder().getReferenceNumber());
        row.setDiscountPercentage(line.getDiscountPercentage());
        if (canViewCostPrice) price(row, line.getCostPrice(), line.getDiscountPercentage());
        return row;
    }

    private TransactionDetailedReportRow fromPurchaseReceive(PurchaseReceiveLine line, boolean canViewCostPrice) {
        PurchaseReceive h = line.getPurchaseReceive();
        TransactionDetailedReportRow row = line(line.getId(), line.getLineNumber(), line.getItem(), line.getQuantity(), line.getQuantityLoaded());
        header(row, h.getId(), h.getCompany(), h.getReferenceNumber(), h.getSheetNumber(), h.getReceiptDate(), h.getWarehouse(), h.getRemarks());
        status(row, h.isVoided(), h.getVoidedAt(), h.getVoidedBy(), h.getCreatedAt(), h.getCreatedBy(), h.getOrigin());
        row.setCounterpartyId(h.getSupplier().getId());
        row.setCounterpartyName(h.getSupplier().getName());
        if (h.getPurchaseOrder() != null) row.setSourceReferenceNumber(h.getPurchaseOrder().getReferenceNumber());
        else if (h.getPurchaseInvoice() != null) row.setSourceReferenceNumber(h.getPurchaseInvoice().getReferenceNumber());
        // A receive line has no price of its own — value it at the cost of the line it received against.
        if (canViewCostPrice) {
            if (line.getPurchaseInvoiceLine() != null) price(row, line.getPurchaseInvoiceLine().getCostPrice(), line.getPurchaseInvoiceLine().getDiscountPercentage());
            else if (line.getPurchaseOrderLine() != null) price(row, line.getPurchaseOrderLine().getCostPrice(), null);
        }
        return row;
    }

    private TransactionDetailedReportRow fromDeliveryReceipt(DeliveryReceiptLine line) {
        DeliveryReceipt h = line.getDeliveryReceipt();
        TransactionDetailedReportRow row = line(line.getId(), line.getLineNumber(), line.getItem(), line.getQuantity(), line.getQuantityLoaded());
        header(row, h.getId(), h.getCompany(), h.getReferenceNumber(), h.getSheetNumber(), h.getDeliveryDate(), h.getWarehouse(), h.getRemarks());
        status(row, h.isVoided(), h.getVoidedAt(), h.getVoidedBy(), h.getCreatedAt(), h.getCreatedBy(), h.getOrigin());
        row.setCounterpartyId(h.getCustomer().getId());
        row.setCounterpartyName(h.getCustomer().getName());
        if (h.getStockTransfer() != null) row.setSourceReferenceNumber(h.getStockTransfer().getReferenceNumber());
        price(row, line.getUnitPrice(), null);
        return row;
    }

    private TransactionDetailedReportRow fromOutletReceive(OutletReceiveLine line) {
        OutletReceive h = line.getOutletReceive();
        TransactionDetailedReportRow row = line(line.getId(), line.getLineNumber(), line.getItem(), line.getQuantity(), line.getQuantityLoaded());
        header(row, h.getId(), h.getCompany(), h.getReferenceNumber(), h.getSheetNumber(), h.getReceiptDate(), h.getWarehouse(), h.getRemarks());
        status(row, h.isVoided(), h.getVoidedAt(), h.getVoidedBy(), h.getCreatedAt(), h.getCreatedBy(), h.getOrigin());
        row.setCounterpartyId(h.getCustomer().getId());
        row.setCounterpartyName(h.getCustomer().getName());
        row.setSourceReferenceNumber(h.getDeliveryReceipt().getReferenceNumber());
        // Valued at the selling price of the delivered line it received.
        price(row, line.getDeliveryReceiptLine().getUnitPrice(), null);
        return row;
    }

    private TransactionDetailedReportRow fromOutletDeliveryReceipt(OutletDeliveryReceiptLine line) {
        OutletDeliveryReceipt h = line.getOutletDeliveryReceipt();
        TransactionDetailedReportRow row = line(line.getId(), line.getLineNumber(), line.getItem(), line.getQuantity(), line.getQuantityLoaded());
        header(row, h.getId(), h.getCompany(), h.getReferenceNumber(), h.getSheetNumber(), h.getDeliveryDate(), h.getWarehouse(), h.getRemarks());
        status(row, h.isVoided(), h.getVoidedAt(), h.getVoidedBy(), h.getCreatedAt(), h.getCreatedBy(), h.getOrigin());
        row.setCounterpartyId(h.getCustomer().getId());
        row.setCounterpartyName(h.getCustomer().getName());
        agent(row, h.getAgent());
        price(row, line.getUnitPrice(), null);
        return row;
    }

    private TransactionDetailedReportRow fromOutletDeliveryReturn(OutletDeliveryReturnLine line) {
        OutletDeliveryReturn h = line.getOutletDeliveryReturn();
        TransactionDetailedReportRow row = line(line.getId(), line.getLineNumber(), line.getItem(), line.getQuantity(), line.getQuantityLoaded());
        header(row, h.getId(), h.getCompany(), h.getReferenceNumber(), h.getSheetNumber(), h.getReturnDate(), h.getWarehouse(), h.getRemarks());
        status(row, h.isVoided(), h.getVoidedAt(), h.getVoidedBy(), h.getCreatedAt(), h.getCreatedBy(), h.getOrigin());
        row.setCounterpartyId(h.getCustomer().getId());
        row.setCounterpartyName(h.getCustomer().getName());
        row.setSourceReferenceNumber(h.getOutletDeliveryReceipt().getReferenceNumber());
        agent(row, h.getAgent());
        price(row, line.getUnitPrice(), null);
        return row;
    }

    private TransactionDetailedReportRow fromStockTransfer(StockTransferLine line) {
        StockTransfer h = line.getStockTransfer();
        TransactionDetailedReportRow row = line(line.getId(), line.getLineNumber(), line.getItem(), line.getQuantity(), line.getQuantityLoaded());
        header(row, h.getId(), h.getCompany(), h.getReferenceNumber(), h.getSheetNumber(), h.getTransferDate(), h.getWarehouse(), h.getRemarks());
        status(row, h.isVoided(), h.getVoidedAt(), h.getVoidedBy(), h.getCreatedAt(), h.getCreatedBy(), h.getOrigin());
        row.setCounterpartyId(h.getCustomer().getId());
        row.setCounterpartyName(h.getCustomer().getName());
        price(row, line.getUnitPrice(), null);
        return row;
    }

    // Warehouse is the outlet's own warehouse — where the stock left from.
    private TransactionDetailedReportRow fromOutletPullOut(OutletPullOutLine line) {
        OutletPullOut h = line.getOutletPullOut();
        TransactionDetailedReportRow row = line(line.getId(), line.getLineNumber(), line.getItem(), line.getQuantity(), line.getQuantityLoaded());
        header(row, h.getId(), h.getCompany(), h.getReferenceNumber(), h.getSheetNumber(), h.getPullOutDate(), h.getWarehouse(), h.getRemarks());
        status(row, h.isVoided(), h.getVoidedAt(), h.getVoidedBy(), h.getCreatedAt(), h.getCreatedBy(), h.getOrigin());
        row.setCounterpartyId(h.getCustomer().getId());
        row.setCounterpartyName(h.getCustomer().getName());
        price(row, line.getUnitPrice(), null);
        return row;
    }

    private TransactionDetailedReportRow fromPullOutReceive(PullOutReceiveLine line) {
        PullOutReceive h = line.getPullOutReceive();
        TransactionDetailedReportRow row = line(line.getId(), line.getLineNumber(), line.getItem(), line.getQuantity(), line.getQuantityLoaded());
        header(row, h.getId(), h.getCompany(), h.getReferenceNumber(), h.getSheetNumber(), h.getReceiptDate(), h.getWarehouse(), h.getRemarks());
        status(row, h.isVoided(), h.getVoidedAt(), h.getVoidedBy(), h.getCreatedAt(), h.getCreatedBy(), h.getOrigin());
        row.setCounterpartyId(h.getCustomer().getId());
        row.setCounterpartyName(h.getCustomer().getName());
        row.setSourceReferenceNumber(h.getOutletPullOut().getReferenceNumber());
        // Valued at the price of the pulled-out line it received.
        price(row, line.getOutletPullOutLine().getUnitPrice(), null);
        return row;
    }

    // Raw materials consumed show as negative quantities, outputs as positive — the line's effect on stock.
    private TransactionDetailedReportRow fromAssembly(AssemblyLine line) {
        Assembly h = line.getAssembly();
        BigDecimal quantity = line.getKind() == AssemblyLineKind.MATERIAL ? line.getQuantity().negate() : line.getQuantity();
        TransactionDetailedReportRow row = line(line.getId(), line.getLineNumber(), line.getItem(), quantity, line.getQuantityLoaded());
        header(row, h.getId(), h.getCompany(), h.getReferenceNumber(), h.getSheetNumber(), h.getAssemblyDate(), h.getWarehouse(), h.getRemarks());
        status(row, h.isVoided(), h.getVoidedAt(), h.getVoidedBy(), h.getCreatedAt(), h.getCreatedBy(), h.getOrigin());
        return row;
    }

    private static void agent(TransactionDetailedReportRow row, Employee agent) {
        row.setAgentId(agent.getId());
        row.setAgentName(agent.getFirstName() + " " + agent.getLastName());
    }

    private static TransactionDetailedReportRow line(Long id, Integer lineNumber, Item item, BigDecimal quantity, BigDecimal quantityLoaded) {
        TransactionDetailedReportRow row = new TransactionDetailedReportRow();
        row.setId(id);
        row.setLineNumber(lineNumber);
        row.setItemId(item.getId());
        row.setItemCode(item.getItemCode());
        row.setItemName(item.getName());
        row.setQuantity(quantity);
        row.setQuantityLoaded(quantityLoaded);
        return row;
    }

    private static void header(TransactionDetailedReportRow row, Long transactionId, Company company, String referenceNumber,
                               String sheetNumber, Instant date, Warehouse warehouse, String remarks) {
        row.setTransactionId(transactionId);
        row.setCompanyId(company.getId());
        row.setCompanyName(company.getName());
        row.setReferenceNumber(referenceNumber);
        row.setSheetNumber(sheetNumber);
        row.setTransactionDate(date);
        row.setWarehouseId(warehouse.getId());
        row.setWarehouseName(warehouse.getName());
        row.setRemarks(remarks);
    }

    private static void status(TransactionDetailedReportRow row, boolean voided, Instant voidedAt, String voidedBy,
                               Instant createdAt, String createdBy, TransactionOrigin origin) {
        row.setVoided(voided);
        row.setOrigin(origin);
        row.setVoidedAt(voidedAt);
        row.setVoidedBy(voidedBy);
        row.setCreatedAt(createdAt);
        row.setCreatedBy(createdBy);
    }

    private static void price(TransactionDetailedReportRow row, BigDecimal price, BigDecimal discountPercentage) {
        row.setPrice(price);
        BigDecimal amount = row.getQuantity().multiply(price);
        if (discountPercentage != null) {
            amount = amount.subtract(amount.multiply(discountPercentage).divide(HUNDRED));
        }
        row.setAmount(amount.setScale(4, RoundingMode.HALF_UP));
    }
}
