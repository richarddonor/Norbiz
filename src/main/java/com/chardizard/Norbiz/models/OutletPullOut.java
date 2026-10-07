package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Not Auditable: immutable once posted (only create/view/void) — the inventory ledger is the audit trail.
// Pulls stock out of an outlet back to the company: deducts on-hand in the outlet's warehouse and posts the
// same quantity as in transit in the main warehouse, later taken in by Pull Out Receive.
@Getter
@Setter
@Entity
@Table(
    name = "outlet_pull_outs",
    uniqueConstraints = @UniqueConstraint(name = "OUTLET_PULL_OUTS_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class OutletPullOut {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_PULL_OUTS_COMPANY_ID_FK"))
    private Company company;

    // The outlet the stock is pulled from.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_PULL_OUTS_CUSTOMER_ID_FK"))
    private Customer customer;

    // Source: the outlet's own warehouse.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_PULL_OUTS_WAREHOUSE_ID_FK"))
    private Warehouse warehouse;

    // Destination: the company's main warehouse at posting time (snapshot), holding the pull out in transit.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_warehouse_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_PULL_OUTS_DESTINATION_WAREHOUSE_ID_FK"))
    private Warehouse destinationWarehouse;

    // Required on create; nullable only for migrated legacy pull outs that had none.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pull_out_reason_id",
        foreignKey = @ForeignKey(name = "OUTLET_PULL_OUTS_PULL_OUT_REASON_ID_FK"))
    private PullOutReason reason;

    // Auto-generated per docs/TRANSACTIONS.md — see TransactionReferenceService.
    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    @Column(name = "sheet_number", length = 100)
    private String sheetNumber;

    // Business-effective date, represented as the UTC-midnight instant of that day (see InventoryMovement).
    @Column(name = "pull_out_date", nullable = false)
    private Instant pullOutDate;

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

    // True once Pull Out Receive(s) have received every line in full.
    @Column(nullable = false)
    private boolean loaded = false;

    // NATIVE for everything posted through the API; MIGRATED/RECONSTRUCTED only from the legacy migration loader.
    @ColumnDefault("'NATIVE'")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionOrigin origin = TransactionOrigin.NATIVE;

    @OneToMany(mappedBy = "outletPullOut", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<OutletPullOutLine> lines = new ArrayList<>();
}
