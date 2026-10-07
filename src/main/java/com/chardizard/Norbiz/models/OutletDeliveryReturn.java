package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Not Auditable: immutable once posted (only create/view/void) — the inventory ledger is the audit trail.
// Takes items sold on an Outlet Delivery Receipt back into the outlet warehouse's on-hand stock.
@Getter
@Setter
@Entity
@Table(
    name = "outlet_delivery_returns",
    uniqueConstraints = @UniqueConstraint(name = "OUTLET_DELIVERY_RETURNS_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class OutletDeliveryReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_DELIVERY_RETURNS_COMPANY_ID_FK"))
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_delivery_receipt_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_DELIVERY_RETURNS_OUTLET_DELIVERY_RECEIPT_ID_FK"))
    private OutletDeliveryReceipt outletDeliveryReceipt;

    // Copied from the Outlet Delivery Receipt: the outlet, its warehouse, and the agent whose commission it reverses.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_DELIVERY_RETURNS_CUSTOMER_ID_FK"))
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_DELIVERY_RETURNS_WAREHOUSE_ID_FK"))
    private Warehouse warehouse;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_DELIVERY_RETURNS_AGENT_ID_FK"))
    private Employee agent;

    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    @Column(name = "sheet_number", length = 100)
    private String sheetNumber;

    @Column(name = "return_date", nullable = false)
    private Instant returnDate;

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

    // Inert: nothing loads from an Outlet Delivery Return. Kept for parity with every other transaction type.
    @Column(nullable = false)
    private boolean loaded = false;

    // NATIVE for everything posted through the API; MIGRATED/RECONSTRUCTED only from the legacy migration loader.
    // ColumnDefault: lets ddl-auto=update add the NOT NULL column to existing rows.
    @ColumnDefault("'NATIVE'")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionOrigin origin = TransactionOrigin.NATIVE;

    @OneToMany(mappedBy = "outletDeliveryReturn", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<OutletDeliveryReturnLine> lines = new ArrayList<>();
}
