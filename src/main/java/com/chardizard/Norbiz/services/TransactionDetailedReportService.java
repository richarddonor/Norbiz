package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.AccountingResponses;
import com.chardizard.Norbiz.dto.DetailedReportFilter;
import com.chardizard.Norbiz.dto.TransactionDetailedReportRow;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import com.chardizard.Norbiz.util.DateRangeUtils;
import com.chardizard.Norbiz.util.ListSort;
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
import java.util.Map;
import java.util.HashMap;
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
    private final PurchaseReceiptLineRepository purchaseReceiptLineRepository;
    private final DeliveryReceiptLineRepository deliveryReceiptLineRepository;
    private final OutletReceiptLineRepository outletReceiptLineRepository;
    private final OutletDeliveryReceiptLineRepository outletDeliveryReceiptLineRepository;
    private final OutletDeliveryReturnLineRepository outletDeliveryReturnLineRepository;
    private final StockTransferLineRepository stockTransferLineRepository;
    private final OutletPullOutLineRepository outletPullOutLineRepository;
    private final PullOutReceiptLineRepository pullOutReceiptLineRepository;
    private final AssemblyLineRepository assemblyLineRepository;
    private final SalesInvoiceLineRepository salesInvoiceLineRepository;
    private final CustomerMemoLineRepository customerMemoLineRepository;
    private final SupplierMemoLineRepository supplierMemoLineRepository;
    private final CustomerPaymentReportLineRepository customerPaymentReportLineRepository;
    private final SupplierPaymentReportLineRepository supplierPaymentReportLineRepository;
    private final CustomerRefundLineRepository customerRefundLineRepository;
    private final SupplierRefundLineRepository supplierRefundLineRepository;
    private final DepositLineRepository depositLineRepository;
    private final BankReconciliationLineRepository bankReconciliationLineRepository;
    private final StatementOfAccountLineRepository statementOfAccountLineRepository;

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
            case PURCHASE_RECEIPT -> query(purchaseReceiptLineRepository,
                    new Shape("purchaseReceipt", "receiptDate", "remarks", "supplier", null, "purchaseOrder", "purchaseInvoice"),
                    type, username, filter, pageable, l -> fromPurchaseReceipt(l, canViewCostPrice));
            case DELIVERY_RECEIPT -> query(deliveryReceiptLineRepository,
                    new Shape("deliveryReceipt", "deliveryDate", "remarks", "customer", null, "stockTransfer"),
                    type, username, filter, pageable, this::fromDeliveryReceipt);
            case OUTLET_RECEIPT -> query(outletReceiptLineRepository,
                    new Shape("outletReceipt", "receiptDate", "remarks", "customer", null, "deliveryReceipt"),
                    type, username, filter, pageable, this::fromOutletReceipt);
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
            case PULL_OUT_RECEIPT -> query(pullOutReceiptLineRepository,
                    new Shape("pullOutReceipt", "receiptDate", "remarks", "customer", null, "outletPullOut"),
                    type, username, filter, pageable, this::fromPullOutReceipt);
            case ASSEMBLY -> query(assemblyLineRepository,
                    new Shape("assembly", "assemblyDate", "remarks", null, null),
                    type, username, filter, pageable, this::fromAssembly);
            case SALES_INVOICE -> query(salesInvoiceLineRepository,
                    new Shape("salesInvoice", "invoiceDate", "remarks", "customer", null, "deliveryReceipt"),
                    type, username, filter, pageable, this::fromSalesInvoice);
            // Accounting documents have no items or warehouse: one row per memo line / tender / application / ...
            case CUSTOMER_MEMO -> queryAmounts(customerMemoLineRepository,
                    new Shape("customerMemo", "memoDate", "remarks", "customer", null), type, username, filter, pageable,
                    l -> amountRow(l.getId(), l.getLineNumber(), l.getCustomerMemo().getId(), l.getCustomerMemo().getCompany(),
                            l.getCustomerMemo().getReferenceNumber(), l.getCustomerMemo().getSheetNumber(), l.getCustomerMemo().getMemoDate(),
                            l.getCustomerMemo().getRemarks(), l.getCustomerMemo().isVoided(), l.getCustomerMemo().getVoidedAt(),
                            l.getCustomerMemo().getVoidedBy(), l.getCustomerMemo().getCreatedAt(), l.getCustomerMemo().getCreatedBy(),
                            l.getCustomerMemo().getOrigin(), l.getCustomerMemo().getCustomer().getId(), l.getCustomerMemo().getCustomer().getName(),
                            l.getCustomerMemo().getMemoType() + " " + l.getCustomerMemo().getReason() + ": " + l.getDescription(),
                            l.getCustomerMemo().getMemoType() == MemoType.DEBIT ? l.getAmount() : l.getAmount().negate()));
            case SUPPLIER_MEMO -> queryAmounts(supplierMemoLineRepository,
                    new Shape("supplierMemo", "memoDate", "remarks", "supplier", null), type, username, filter, pageable,
                    l -> amountRow(l.getId(), l.getLineNumber(), l.getSupplierMemo().getId(), l.getSupplierMemo().getCompany(),
                            l.getSupplierMemo().getReferenceNumber(), l.getSupplierMemo().getSheetNumber(), l.getSupplierMemo().getMemoDate(),
                            l.getSupplierMemo().getRemarks(), l.getSupplierMemo().isVoided(), l.getSupplierMemo().getVoidedAt(),
                            l.getSupplierMemo().getVoidedBy(), l.getSupplierMemo().getCreatedAt(), l.getSupplierMemo().getCreatedBy(),
                            l.getSupplierMemo().getOrigin(), l.getSupplierMemo().getSupplier().getId(), l.getSupplierMemo().getSupplier().getName(),
                            l.getSupplierMemo().getMemoType() + " " + l.getSupplierMemo().getReason() + ": " + l.getDescription(),
                            l.getSupplierMemo().getMemoType() == MemoType.CREDIT ? l.getAmount() : l.getAmount().negate()));
            case CUSTOMER_PAYMENT -> queryAmounts(customerPaymentReportLineRepository,
                    new Shape("customerPayment", "paymentDate", "remarks", "customer", null), type, username, filter, pageable,
                    t -> amountRow(t.getId(), t.getLineNumber(), t.getCustomerPayment().getId(), t.getCustomerPayment().getCompany(),
                            t.getCustomerPayment().getReferenceNumber(), t.getCustomerPayment().getSheetNumber(), t.getCustomerPayment().getPaymentDate(),
                            t.getCustomerPayment().getRemarks(), t.getCustomerPayment().isVoided(), t.getCustomerPayment().getVoidedAt(),
                            t.getCustomerPayment().getVoidedBy(), t.getCustomerPayment().getCreatedAt(), t.getCustomerPayment().getCreatedBy(),
                            t.getCustomerPayment().getOrigin(), t.getCustomerPayment().getCustomer().getId(), t.getCustomerPayment().getCustomer().getName(),
                            paymentLineDescription(t.getMode(), t.getCheckNumber(), t.getStatus(), t.getCreditSourceType(),
                                    t.getCreditReference(), t.getChargeReference()), t.getAmount()));
            case SUPPLIER_PAYMENT -> queryAmounts(supplierPaymentReportLineRepository,
                    new Shape("supplierPayment", "paymentDate", "remarks", "supplier", null), type, username, filter, pageable,
                    t -> amountRow(t.getId(), t.getLineNumber(), t.getSupplierPayment().getId(), t.getSupplierPayment().getCompany(),
                            t.getSupplierPayment().getReferenceNumber(), t.getSupplierPayment().getSheetNumber(), t.getSupplierPayment().getPaymentDate(),
                            t.getSupplierPayment().getRemarks(), t.getSupplierPayment().isVoided(), t.getSupplierPayment().getVoidedAt(),
                            t.getSupplierPayment().getVoidedBy(), t.getSupplierPayment().getCreatedAt(), t.getSupplierPayment().getCreatedBy(),
                            t.getSupplierPayment().getOrigin(), t.getSupplierPayment().getSupplier().getId(), t.getSupplierPayment().getSupplier().getName(),
                            paymentLineDescription(t.getMode(), t.getCheckNumber(), t.getStatus(), t.getCreditSourceType(),
                                    t.getCreditReference(), t.getChargeReference()), t.getAmount()));
            case CUSTOMER_REFUND -> queryAmounts(customerRefundLineRepository,
                    new Shape("customerRefund", "refundDate", "remarks", "customer", null), type, username, filter, pageable,
                    l -> amountRow(l.getId(), l.getLineNumber(), l.getCustomerRefund().getId(), l.getCustomerRefund().getCompany(),
                            l.getCustomerRefund().getReferenceNumber(), l.getCustomerRefund().getSheetNumber(), l.getCustomerRefund().getRefundDate(),
                            l.getCustomerRefund().getRemarks(), l.getCustomerRefund().isVoided(), l.getCustomerRefund().getVoidedAt(),
                            l.getCustomerRefund().getVoidedBy(), l.getCustomerRefund().getCreatedAt(), l.getCustomerRefund().getCreatedBy(),
                            l.getCustomerRefund().getOrigin(), l.getCustomerRefund().getCustomer().getId(), l.getCustomerRefund().getCustomer().getName(),
                            "Refund of " + l.getCreditItem().getReferenceNumber(), l.getAmount()));
            case SUPPLIER_REFUND -> queryAmounts(supplierRefundLineRepository,
                    new Shape("supplierRefund", "refundDate", "remarks", "supplier", null), type, username, filter, pageable,
                    l -> amountRow(l.getId(), l.getLineNumber(), l.getSupplierRefund().getId(), l.getSupplierRefund().getCompany(),
                            l.getSupplierRefund().getReferenceNumber(), l.getSupplierRefund().getSheetNumber(), l.getSupplierRefund().getRefundDate(),
                            l.getSupplierRefund().getRemarks(), l.getSupplierRefund().isVoided(), l.getSupplierRefund().getVoidedAt(),
                            l.getSupplierRefund().getVoidedBy(), l.getSupplierRefund().getCreatedAt(), l.getSupplierRefund().getCreatedBy(),
                            l.getSupplierRefund().getOrigin(), l.getSupplierRefund().getSupplier().getId(), l.getSupplierRefund().getSupplier().getName(),
                            "Refund of " + l.getCreditItem().getReferenceNumber(), l.getAmount()));
            case DEPOSIT -> queryAmounts(depositLineRepository,
                    new Shape("deposit", "depositDate", "remarks", null, null), type, username, filter, pageable,
                    l -> amountRow(l.getId(), l.getLineNumber(), l.getDeposit().getId(), l.getDeposit().getCompany(),
                            l.getDeposit().getReferenceNumber(), l.getDeposit().getSheetNumber(), l.getDeposit().getDepositDate(),
                            l.getDeposit().getRemarks(), l.getDeposit().isVoided(), l.getDeposit().getVoidedAt(), l.getDeposit().getVoidedBy(),
                            l.getDeposit().getCreatedAt(), l.getDeposit().getCreatedBy(), l.getDeposit().getOrigin(),
                            l.getTender().getCustomerPayment().getCustomer().getId(), l.getTender().getCustomerPayment().getCustomer().getName(),
                            l.getTender().getMode() + (l.getTender().getCheckNumber() != null ? " #" + l.getTender().getCheckNumber() : "")
                                    + " of " + l.getTender().getCustomerPayment().getReferenceNumber(), l.getAmount()));
            case BANK_RECONCILIATION -> queryAmounts(bankReconciliationLineRepository,
                    new Shape("bankReconciliation", "statementDate", "remarks", null, null), type, username, filter, pageable,
                    l -> amountRow(l.getId(), l.getLineNumber(), l.getBankReconciliation().getId(), l.getBankReconciliation().getCompany(),
                            l.getBankReconciliation().getReferenceNumber(), l.getBankReconciliation().getSheetNumber(),
                            l.getBankReconciliation().getStatementDate(), l.getBankReconciliation().getRemarks(), l.getBankReconciliation().isVoided(),
                            l.getBankReconciliation().getVoidedAt(), l.getBankReconciliation().getVoidedBy(), l.getBankReconciliation().getCreatedAt(),
                            l.getBankReconciliation().getCreatedBy(), l.getBankReconciliation().getOrigin(), null, null,
                            (l.getKind() == BankReconciliationLineKind.ADJUSTMENT ? l.getAdjustmentType() + ": " : l.getReferenceNumber() + " ")
                                    + (l.getCheckNumber() != null ? "#" + l.getCheckNumber() + " " : "") + (l.getDescription() != null ? l.getDescription() : ""),
                            l.getAmount()));
            case STATEMENT_OF_ACCOUNT -> queryAmounts(statementOfAccountLineRepository,
                    new Shape("statementOfAccount", "statementDate", "remarks", "customer", null), type, username, filter, pageable,
                    l -> amountRow(l.getId(), l.getLineNumber(), l.getStatementOfAccount().getId(), l.getStatementOfAccount().getCompany(),
                            l.getStatementOfAccount().getReferenceNumber(), l.getStatementOfAccount().getSheetNumber(),
                            l.getStatementOfAccount().getStatementDate(), l.getStatementOfAccount().getRemarks(), l.getStatementOfAccount().isVoided(),
                            l.getStatementOfAccount().getVoidedAt(), l.getStatementOfAccount().getVoidedBy(), l.getStatementOfAccount().getCreatedAt(),
                            l.getStatementOfAccount().getCreatedBy(), l.getStatementOfAccount().getOrigin(),
                            l.getStatementOfAccount().getCustomer().getId(), l.getStatementOfAccount().getCustomer().getName(),
                            l.getReferenceNumber() + (l.getDaysOverdue() > 0 ? " (" + l.getDaysOverdue() + " days overdue)" : ""), l.getBalance()));
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
                // Any source matches — Purchase Receipt loads from either a PO or a Direct invoice.
                List<Predicate> any = new ArrayList<>();
                for (String source : shape.sources()) {
                    addContains(cb, any, f.getSourceReferenceNumber(), header.join(source, JoinType.LEFT).get("referenceNumber"));
                }
                predicates.add(cb.or(any.toArray(Predicate[]::new)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        // The requested column sort (ListSort), then a fixed total ordering — newest transaction first,
        // lines in entry order — so export (which pages through every page) never skips or repeats a row.
        Sort sort = ListSort.resolve(pageable.getSort(), sortable(shape)).and(Sort.by(
                Sort.Order.desc(shape.header() + ".createdAt"),
                Sort.Order.desc(shape.header() + ".id"),
                Sort.Order.asc("lineNumber"),
                Sort.Order.asc("id")));
        Pageable sorted = pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort)
                : Pageable.unpaged(sort);
        return repository.findAll(spec, sorted).map(mapper);
    }

    /**
     * Like {@link #query} for the accounting documents, whose lines have no item and whose header has no warehouse:
     * item/warehouse filters are rejected, and amount (not quantity) is sortable.
     */
    private <L> Page<TransactionDetailedReportRow> queryAmounts(JpaSpecificationExecutor<L> repository, Shape shape,
                                                                DetailedReportType type, String username, DetailedReportFilter f,
                                                                Pageable pageable, Function<L, TransactionDetailedReportRow> mapper) {
        rejectInapplicableFilters(shape, type, f);
        if (f.getWarehouseId() != null || f.getItemId() != null || StringUtils.hasText(f.getWarehouse())
                || StringUtils.hasText(f.getItemCode()) || StringUtils.hasText(f.getItemName())) {
            throw new IllegalArgumentException("Item and warehouse filters do not apply to " + type.getDisplayName());
        }
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
            List<Predicate> predicates = new ArrayList<>();
            if (companyIds != null) predicates.add(header.get("company").get("id").in(companyIds));
            if (counterpartyId != null) predicates.add(cb.equal(header.get(shape.counterparty()).get("id"), counterpartyId));
            if (f.getVoided() != null) predicates.add(cb.equal(header.get("voided"), f.getVoided()));
            if (f.getOrigin() != null) predicates.add(cb.equal(header.get("origin"), f.getOrigin()));
            if (dateFrom != null) predicates.add(cb.greaterThanOrEqualTo(header.<Instant>get(shape.date()), dateFrom));
            if (dateTo != null) predicates.add(cb.lessThanOrEqualTo(header.<Instant>get(shape.date()), dateTo));
            addContains(cb, predicates, f.getReferenceNumber(), header.get("referenceNumber"));
            addContains(cb, predicates, f.getSheetNumber(), header.get("sheetNumber"));
            addContains(cb, predicates, f.getRemarks(), header.get(shape.remarks()));
            if (StringUtils.hasText(f.getCounterparty())) {
                addContains(cb, predicates, f.getCounterparty(), header.get(shape.counterparty()).get("name"));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        String h = shape.header() + ".";
        Map<String, String> sortable = new HashMap<>(ListSort.fields(
                "company", h + "company.name",
                "referenceNumber", h + "referenceNumber",
                "sheetNumber", h + "sheetNumber",
                "date", h + shape.date(),
                "lineNumber", "lineNumber",
                "amount", "amount",
                "voided", h + "voided"));
        if (shape.counterparty() != null) sortable.put("counterparty", h + shape.counterparty() + ".name");
        Sort sort = ListSort.resolve(pageable.getSort(), sortable).and(Sort.by(
                Sort.Order.desc(h + "createdAt"),
                Sort.Order.desc(h + "id"),
                Sort.Order.asc("lineNumber"),
                Sort.Order.asc("id")));
        Pageable sorted = pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort)
                : Pageable.unpaged(sort);
        return repository.findAll(spec, sorted).map(mapper);
    }

    /** Report column key → line-entity path for ?sort= (ListSort). */
    private static Map<String, String> sortable(Shape shape) {
        String h = shape.header() + ".";
        Map<String, String> fields = new HashMap<>(ListSort.fields(
                "company", h + "company.name",
                "referenceNumber", h + "referenceNumber",
                "sheetNumber", h + "sheetNumber",
                "date", h + shape.date(),
                "warehouse", h + "warehouse.name",
                "itemCode", "item.itemCode",
                "itemName", "item.name",
                "lineNumber", "lineNumber",
                "quantity", "quantity",
                "voided", h + "voided"));
        if (shape.counterparty() != null) fields.put("counterparty", h + shape.counterparty() + ".name");
        return fields;
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

    private TransactionDetailedReportRow fromPurchaseReceipt(PurchaseReceiptLine line, boolean canViewCostPrice) {
        PurchaseReceipt h = line.getPurchaseReceipt();
        TransactionDetailedReportRow row = line(line.getId(), line.getLineNumber(), line.getItem(), line.getQuantity(), line.getQuantityLoaded());
        header(row, h.getId(), h.getCompany(), h.getReferenceNumber(), h.getSheetNumber(), h.getReceiptDate(), h.getWarehouse(), h.getRemarks());
        status(row, h.isVoided(), h.getVoidedAt(), h.getVoidedBy(), h.getCreatedAt(), h.getCreatedBy(), h.getOrigin());
        row.setCounterpartyId(h.getSupplier().getId());
        row.setCounterpartyName(h.getSupplier().getName());
        if (h.getPurchaseOrder() != null) row.setSourceReferenceNumber(h.getPurchaseOrder().getReferenceNumber());
        else if (h.getPurchaseInvoice() != null) row.setSourceReferenceNumber(h.getPurchaseInvoice().getReferenceNumber());
        // A receipt line has no price of its own — value it at the cost of the line it received against.
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

    private TransactionDetailedReportRow fromOutletReceipt(OutletReceiptLine line) {
        OutletReceipt h = line.getOutletReceipt();
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

    private TransactionDetailedReportRow fromPullOutReceipt(PullOutReceiptLine line) {
        PullOutReceipt h = line.getPullOutReceipt();
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

    private TransactionDetailedReportRow fromSalesInvoice(SalesInvoiceLine line) {
        SalesInvoice h = line.getSalesInvoice();
        TransactionDetailedReportRow row = line(line.getId(), line.getLineNumber(), line.getItem(), line.getQuantity(), BigDecimal.ZERO);
        header(row, h.getId(), h.getCompany(), h.getReferenceNumber(), h.getSheetNumber(), h.getInvoiceDate(), h.getWarehouse(), h.getRemarks());
        status(row, h.isVoided(), h.getVoidedAt(), h.getVoidedBy(), h.getCreatedAt(), h.getCreatedBy(), h.getOrigin());
        row.setCounterpartyId(h.getCustomer().getId());
        row.setCounterpartyName(h.getCustomer().getName());
        if (h.getDeliveryReceipt() != null) row.setSourceReferenceNumber(h.getDeliveryReceipt().getReferenceNumber());
        row.setDiscountPercentage(line.getDiscountPercentage());
        price(row, line.getUnitPrice(), line.getDiscountPercentage());
        return row;
    }

    // One accounting-document line: header fields, the counterparty (null for deposits/reconciliations), a description
    // and a signed amount (memos: negative when it lowers the balance).
    // A payment line: a tender ("CHECK #123 (ISSUED)") or a credit used ("Supplier Debit Memo SDM-0004 → PI-0012").
    private static String paymentLineDescription(String mode, String checkNumber, String status, OpenItemSourceType creditSourceType,
                                                 String creditReference, String chargeReference) {
        if ("CREDIT".equals(mode)) {
            return AccountingResponses.documentType(creditSourceType) + " " + creditReference + " → " + chargeReference;
        }
        return mode + (checkNumber != null ? " #" + checkNumber : "") + " (" + status + ")";
    }

    private static TransactionDetailedReportRow amountRow(Long id, Integer lineNumber, Long transactionId, Company company, String referenceNumber,
                                                          String sheetNumber, Instant date, String remarks, boolean voided, Instant voidedAt,
                                                          String voidedBy, Instant createdAt, String createdBy, TransactionOrigin origin,
                                                          Long counterpartyId, String counterpartyName, String description, BigDecimal amount) {
        TransactionDetailedReportRow row = new TransactionDetailedReportRow();
        row.setId(id);
        row.setLineNumber(lineNumber);
        row.setTransactionId(transactionId);
        row.setCompanyId(company.getId());
        row.setCompanyName(company.getName());
        row.setReferenceNumber(referenceNumber);
        row.setSheetNumber(sheetNumber);
        row.setTransactionDate(date);
        row.setRemarks(remarks);
        status(row, voided, voidedAt, voidedBy, createdAt, createdBy, origin);
        row.setCounterpartyId(counterpartyId);
        row.setCounterpartyName(counterpartyName);
        row.setDescription(description != null && description.length() > 255 ? description.substring(0, 255) : description);
        row.setAmount(amount);
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
