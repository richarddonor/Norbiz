package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
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
    uniqueConstraints = {
        @UniqueConstraint(name = "PURCHASE_INVOICES_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"}),
        @UniqueConstraint(name = "PURCHASE_INVOICES_PURCHASE_ORDER_UQ", columnNames = {"purchase_order_id"})
    }
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

    // Set at creation only; changing it is future Supplier Payments module work.
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

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

    // Set once this invoice has been fully received via the future Purchase Receive transaction.
    // Only meaningful for Direct-mode invoices — PO-based invoices have nothing of their own to load
    // (the goods movement already belongs to the originating PurchaseOrder).
    @Column(nullable = false)
    private boolean loaded = false;

    @OneToMany(mappedBy = "purchaseInvoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PurchaseInvoiceLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "purchaseInvoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PurchaseInvoiceFee> fees = new ArrayList<>();
}
