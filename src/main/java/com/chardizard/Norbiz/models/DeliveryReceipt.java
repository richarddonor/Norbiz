package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Not Auditable: immutable once posted (only create/view/void) — the inventory ledger is the audit trail.
// Delivers items to a Customer out of the company's main warehouse; for an OUTLET customer the same
// quantity is also posted as in-transit in the outlet's own warehouse, later received by Outlet Receive.
@Getter
@Setter
@Entity
@Table(
    name = "delivery_receipts",
    uniqueConstraints = @UniqueConstraint(name = "DELIVERY_RECEIPTS_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class DeliveryReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "DELIVERY_RECEIPTS_COMPANY_ID_FK"))
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false,
        foreignKey = @ForeignKey(name = "DELIVERY_RECEIPTS_CUSTOMER_ID_FK"))
    private Customer customer;

    // Source: the company's main warehouse at posting time (snapshot — the main flag may move later).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false,
        foreignKey = @ForeignKey(name = "DELIVERY_RECEIPTS_WAREHOUSE_ID_FK"))
    private Warehouse warehouse;

    // OUTLET customers only: the outlet's warehouse that received the in-transit posting. Null for a plain customer.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_warehouse_id",
        foreignKey = @ForeignKey(name = "DELIVERY_RECEIPTS_DESTINATION_WAREHOUSE_ID_FK"))
    private Warehouse destinationWarehouse;

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

    // True once Outlet Receive(s) have received every line in full. Always false for a plain-customer DR.
    @Column(nullable = false)
    private boolean loaded = false;

    @OneToMany(mappedBy = "deliveryReceipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<DeliveryReceiptLine> lines = new ArrayList<>();
}
