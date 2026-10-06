package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Not Auditable: immutable once posted (only create/view/void) — the inventory ledger is the audit trail.
// Records an outlet's sales to (untracked) end customers, deducting on-hand stock from the outlet's own
// warehouse. The agent earns the sale's commission. Returned by Outlet Delivery Return.
@Getter
@Setter
@Entity
@Table(
    name = "outlet_delivery_receipts",
    uniqueConstraints = @UniqueConstraint(name = "OUTLET_DELIVERY_RECEIPTS_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class OutletDeliveryReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_DELIVERY_RECEIPTS_COMPANY_ID_FK"))
    private Company company;

    // The OUTLET customer that made the sale (the end customer isn't tracked).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_DELIVERY_RECEIPTS_CUSTOMER_ID_FK"))
    private Customer customer;

    // The outlet's warehouse at posting time — where on-hand stock is deducted.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_DELIVERY_RECEIPTS_WAREHOUSE_ID_FK"))
    private Warehouse warehouse;

    // Sales agent (Employee tagged AGENT) credited with the commission for this sale.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_DELIVERY_RECEIPTS_AGENT_ID_FK"))
    private Employee agent;

    // Auto-generated per docs/TRANSACTIONS.md — see TransactionReferenceService.
    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    @Column(name = "sheet_number", length = 100)
    private String sheetNumber;

    // Business-effective date, represented as the UTC-midnight instant of that day (see InventoryMovement).
    @Column(name = "delivery_date", nullable = false)
    private Instant deliveryDate;

    @Column(length = 255)
    private String remarks;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(nullable = false)
    private boolean voided = false;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by", length = 100)
    private String voidedBy;

    // True once Outlet Delivery Return(s) have returned every line in full.
    @Column(nullable = false)
    private boolean loaded = false;

    @OneToMany(mappedBy = "outletDeliveryReceipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<OutletDeliveryReceiptLine> lines = new ArrayList<>();
}
