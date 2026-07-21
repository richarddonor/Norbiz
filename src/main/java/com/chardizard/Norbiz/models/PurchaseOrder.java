package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Not Auditable: purchase orders are immutable once posted (no update endpoint, only void),
// so there is nothing to diff/track over time — same reasoning as InventoryAdjustment.
@Getter
@Setter
@Entity
@Table(
    name = "purchase_orders",
    uniqueConstraints = @UniqueConstraint(name = "PURCHASE_ORDERS_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_ORDERS_COMPANY_ID_FK"))
    private Company company;

    // Destination warehouse — creating a PO posts a Transit Quantity increase here
    // (see PurchaseOrderService.postTransitMovement).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_ORDERS_WAREHOUSE_ID_FK"))
    private Warehouse warehouse;

    // Counterparty for this transaction type — see docs/TRANSACTIONS.md "Standard transaction document layout".
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_ORDERS_SUPPLIER_ID_FK"))
    private Supplier supplier;

    // Auto-generated per CLAUDE.md's Transactions rule — see TransactionReferenceService.
    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    // User-supplied control number from the physical source document, if any.
    @Column(name = "sheet_number", length = 100)
    private String sheetNumber;

    // Business-effective date, represented as the UTC-midnight instant of that day (see InventoryMovement).
    @Column(name = "order_date", nullable = false)
    private Instant orderDate;

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

    // Set once this PO has been fully received via the future Purchase Receive transaction
    // (see docs/TRANSACTIONS.md "Transaction Loading") — inert until that transaction exists.
    @Column(nullable = false)
    private boolean loaded = false;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PurchaseOrderLine> lines = new ArrayList<>();
}
