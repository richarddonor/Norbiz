package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

// Not Auditable: immutable once posted (only create/view/void) — the open-item allocation ledger is the audit trail.
// Bills a customer: opens a RECEIVABLE CHARGE open item for netAmount (none when it is zero). Either Direct (its own lines; deducts the
// company's main warehouse stock) or billing one Delivery Receipt in full (lines copied; no stock effect) —
// see docs/ACCOUNTING.md "Sales Invoice".
@Getter
@Setter
@Entity
@Table(
    name = "sales_invoices",
    uniqueConstraints = @UniqueConstraint(name = "SALES_INVOICES_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class SalesInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "SALES_INVOICES_COMPANY_ID_FK"))
    private Company company;

    // Counterparty — see docs/TRANSACTIONS.md "Standard transaction document layout".
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false,
        foreignKey = @ForeignKey(name = "SALES_INVOICES_CUSTOMER_ID_FK"))
    private Customer customer;

    // Direct: the main warehouse the stock left (snapshot). From a DR: the DR's source warehouse, for record only.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false,
        foreignKey = @ForeignKey(name = "SALES_INVOICES_WAREHOUSE_ID_FK"))
    private Warehouse warehouse;

    // Null = Direct mode. Set = bills this Delivery Receipt in full (DeliveryReceipt.billed).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_receipt_id",
        foreignKey = @ForeignKey(name = "SALES_INVOICES_DELIVERY_RECEIPT_ID_FK"))
    private DeliveryReceipt deliveryReceipt;

    // Auto-generated per docs/TRANSACTIONS.md — see TransactionReferenceService.
    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    // User-supplied control number from the physical source document, if any.
    @Column(name = "sheet_number", length = 100)
    private String sheetNumber;

    // Business-effective date, represented as the UTC-midnight instant of that day (see InventoryMovement).
    @Column(name = "invoice_date", nullable = false)
    private Instant invoiceDate;

    @Column(name = "remarks", length = 255)
    private String remarks;

    // Payment terms in days, defaulted from Customer.termDays.
    @Column(name = "term_days", nullable = false)
    private int termDays = 0;

    // invoiceDate + termDays unless overridden on the request.
    @Column(name = "due_date", nullable = false)
    private Instant dueDate;

    // Transaction-level discount percentage (0-100), applied on top of per-line discounts.
    @Column(name = "discount_percentage", nullable = false, precision = 19, scale = 2)
    private BigDecimal discountPercentage = BigDecimal.ZERO;

    // Stored at posting: sum of line nets less the header discount, rounded HALF_UP to 2 decimals (SalesInvoiceAmounts).
    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;

    // Kept in sync with the open item by OpenItemService.
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    // The RECEIVABLE CHARGE this invoice opened. Null for a zero-value invoice (free goods, testers): it owes nothing
    // and is PAID from the start.
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "open_item_id", unique = true,
        foreignKey = @ForeignKey(name = "SALES_INVOICES_OPEN_ITEM_ID_FK"))
    private OpenItem openItem;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    // Voiding is the only sanctioned way to cancel an immutable transaction — see docs/TRANSACTIONS.md "Voiding".
    @Column(name = "voided", nullable = false)
    private boolean voided = false;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by", length = 100)
    private String voidedBy;

    // Fully returned: every line's quantityLoaded has reached its quantity (Sales Invoice Return loads it).
    @ColumnDefault("false")
    @Column(name = "loaded", nullable = false)
    private boolean loaded = false;

    // NATIVE for everything posted through the API.
    // ColumnDefault: lets ddl-auto=update add the NOT NULL column to existing rows.
    @ColumnDefault("'NATIVE'")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionOrigin origin = TransactionOrigin.NATIVE;

    @OneToMany(mappedBy = "salesInvoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<SalesInvoiceLine> lines = new ArrayList<>();
}
