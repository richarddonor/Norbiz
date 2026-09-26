package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Not Auditable: purchase receives are immutable once posted (no update endpoint, only void),
// same reasoning as PurchaseOrder/PurchaseInvoice.
@Getter
@Setter
@Entity
@Table(
    name = "purchase_receives",
    uniqueConstraints = @UniqueConstraint(name = "PURCHASE_RECEIVES_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class PurchaseReceive {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_RECEIVES_COMPANY_ID_FK"))
    private Company company;

    // Destination warehouse — must match the source (PO or Direct invoice)'s own warehouse.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_RECEIVES_WAREHOUSE_ID_FK"))
    private Warehouse warehouse;

    // Counterparty for this transaction type — see docs/TRANSACTIONS.md "Standard transaction document layout".
    // Must match the source's own supplier.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_RECEIVES_SUPPLIER_ID_FK"))
    private Supplier supplier;

    // Exactly one of purchaseOrder / purchaseInvoice is set — see docs/TRANSACTIONS.md "Purchase Receive".
    // No unique constraint here (unlike PurchaseInvoice's PO link): a PO or Direct invoice can be
    // received across multiple partial Purchase Receives.
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "purchase_order_id",
        foreignKey = @ForeignKey(name = "PURCHASE_RECEIVES_PURCHASE_ORDER_ID_FK"))
    private PurchaseOrder purchaseOrder;

    // Set only for a Direct-mode invoice (PO-based invoices have nothing of their own to receive —
    // receive against the originating PurchaseOrder instead).
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "purchase_invoice_id",
        foreignKey = @ForeignKey(name = "PURCHASE_RECEIVES_PURCHASE_INVOICE_ID_FK"))
    private PurchaseInvoice purchaseInvoice;

    // Auto-generated per CLAUDE.md's Transactions rule — see TransactionReferenceService.
    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    // User-supplied control number from the physical source document, if any.
    @Column(name = "sheet_number", length = 100)
    private String sheetNumber;

    // Business-effective date, represented as the UTC-midnight instant of that day (see InventoryMovement).
    @Column(name = "receipt_date", nullable = false)
    private Instant receiptDate;

    @Column(length = 255)
    private String remarks;

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

    // Inert: nothing currently loads from a Purchase Receive, same as InventoryAdjustment.
    @Column(nullable = false)
    private boolean loaded = false;

    @OneToMany(mappedBy = "purchaseReceive", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PurchaseReceiveLine> lines = new ArrayList<>();
}
