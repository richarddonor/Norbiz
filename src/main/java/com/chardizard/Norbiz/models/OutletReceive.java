package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Not Auditable: immutable once posted (only create/view/void) — the inventory ledger is the audit trail.
// Receives an outlet Delivery Receipt's in-transit quantity into the outlet warehouse's on-hand stock.
@Getter
@Setter
@Entity
@Table(
    name = "outlet_receives",
    uniqueConstraints = @UniqueConstraint(name = "OUTLET_RECEIVES_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class OutletReceive {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_RECEIVES_COMPANY_ID_FK"))
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_receipt_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_RECEIVES_DELIVERY_RECEIPT_ID_FK"))
    private DeliveryReceipt deliveryReceipt;

    // Copied from the Delivery Receipt: the outlet customer and its warehouse.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_RECEIVES_CUSTOMER_ID_FK"))
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_RECEIVES_WAREHOUSE_ID_FK"))
    private Warehouse warehouse;

    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    @Column(name = "sheet_number", length = 100)
    private String sheetNumber;

    @Column(name = "receipt_date", nullable = false)
    private Instant receiptDate;

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

    // Inert: nothing loads from an Outlet Receive. Kept for parity with every other transaction type.
    @Column(nullable = false)
    private boolean loaded = false;

    // NATIVE for everything posted through the API; MIGRATED/RECONSTRUCTED only from the legacy migration loader.
    // ColumnDefault: lets ddl-auto=update add the NOT NULL column to existing rows.
    @ColumnDefault("'NATIVE'")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionOrigin origin = TransactionOrigin.NATIVE;

    @OneToMany(mappedBy = "outletReceive", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<OutletReceiveLine> lines = new ArrayList<>();
}
