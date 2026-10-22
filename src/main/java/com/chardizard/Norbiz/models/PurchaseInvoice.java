package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Not Auditable: purchase invoices are immutable once posted (no update endpoint, only void),
// same reasoning as PurchaseOrder/InventoryAdjustment.
@Getter
@Setter
@Entity
@Table(
    name = "purchase_invoices",
    // One non-voided invoice per PO is enforced by the partial unique index PURCHASE_INVOICES_PURCHASE_ORDER_UQ
    // (db/init.sql, WHERE NOT voided) — JPA can't declare a partial index, and a plain unique constraint would
    // stop a PO from being invoiced again after its invoice was voided.
    uniqueConstraints = @UniqueConstraint(name = "PURCHASE_INVOICES_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class PurchaseInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_INVOICES_COMPANY_ID_FK"))
    private Company company;

    // Direct mode posts a Transit Quantity increase here (see PurchaseInvoiceService.postTransitMovement).
    // PO-based mode carries the PO's own warehouse through, for record purposes only.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_INVOICES_WAREHOUSE_ID_FK"))
    private Warehouse warehouse;

    // Counterparty for this transaction type — see docs/TRANSACTIONS.md "Standard transaction document layout".
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_INVOICES_SUPPLIER_ID_FK"))
    private Supplier supplier;

    // Null = Direct mode (no backing PO). Non-null = PO-based mode, loaded in full 1:1 — see docs/TRANSACTIONS.md "Purchase Invoice".
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "purchase_order_id",
        foreignKey = @ForeignKey(name = "PURCHASE_INVOICES_PURCHASE_ORDER_ID_FK"))
    private PurchaseOrder purchaseOrder;

    // Auto-generated per CLAUDE.md's Transactions rule — see TransactionReferenceService.
    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    // User-supplied control number from the physical source document, if any.
    @Column(name = "sheet_number", length = 100)
    private String sheetNumber;

    // Business-effective date, represented as the UTC-midnight instant of that day (see InventoryMovement).
    @Column(name = "invoice_date", nullable = false)
    private Instant invoiceDate;

    @Column(length = 255)
    private String remarks;

    // Transaction-level discount percentage (0-100), applied on top of per-line discounts.
    @Column(name = "discount_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercentage = BigDecimal.ZERO;

    // Kept in sync with the invoice's open item by OpenItemService as supplier payments/applications post and
    // reverse. Untracked invoices (dated before the accounting start date, or migrated) keep the value they had.
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    // Stored at posting: items less header discount plus fees, rounded HALF_UP to 2 decimals (PurchaseInvoiceAmounts).
    // ColumnDefault: lets ddl-auto=update add the NOT NULL column to existing rows (db/migrations backfills it).
    @ColumnDefault("0")
    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount = BigDecimal.ZERO;

    // Payment terms in days, defaulted from Supplier.termDays.
    @ColumnDefault("0")
    @Column(name = "term_days", nullable = false)
    private int termDays = 0;

    // invoiceDate + termDays unless overridden on the request. Null only on rows from before the accounting module.
    @Column(name = "due_date")
    private Instant dueDate;

    // The PAYABLE CHARGE this invoice opened. Null = untracked: dated before the company's accounting start
    // date (or no start date set yet), or written by the legacy migration — see docs/ACCOUNTING.md.
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "open_item_id", unique = true,
        foreignKey = @ForeignKey(name = "PURCHASE_INVOICES_OPEN_ITEM_ID_FK"))
    private OpenItem openItem;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    // Voiding is the only sanctioned way to cancel an immutable transaction — see docs/TRANSACTIONS.md "Voiding".
    @Column(nullable = false)
    private boolean voided = false;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by", length = 100)
    private String voidedBy;

    // Set once this invoice has been fully received via the future Purchase Receipt transaction.
    // Only meaningful for Direct-mode invoices — PO-based invoices have nothing of their own to load
    // (the goods movement already belongs to the originating PurchaseOrder).
    @Column(nullable = false)
    private boolean loaded = false;

    // NATIVE for everything posted through the API; MIGRATED/RECONSTRUCTED only from the legacy migration loader.
    // ColumnDefault: lets ddl-auto=update add the NOT NULL column to existing rows.
    @ColumnDefault("'NATIVE'")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionOrigin origin = TransactionOrigin.NATIVE;

    @OneToMany(mappedBy = "purchaseInvoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<PurchaseInvoiceLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "purchaseInvoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PurchaseInvoiceFee> fees = new ArrayList<>();
}
