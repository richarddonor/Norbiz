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
// Receives an Outlet Pull Out's in-transit quantity into the main warehouse's on-hand stock.
@Getter
@Setter
@Entity
@Table(
    name = "pull_out_receives",
    uniqueConstraints = @UniqueConstraint(name = "PULL_OUT_RECEIVES_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class PullOutReceive {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "PULL_OUT_RECEIVES_COMPANY_ID_FK"))
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_pull_out_id", nullable = false,
        foreignKey = @ForeignKey(name = "PULL_OUT_RECEIVES_OUTLET_PULL_OUT_ID_FK"))
    private OutletPullOut outletPullOut;

    // Copied from the Outlet Pull Out: the outlet and the main warehouse it was pulled into.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false,
        foreignKey = @ForeignKey(name = "PULL_OUT_RECEIVES_CUSTOMER_ID_FK"))
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false,
        foreignKey = @ForeignKey(name = "PULL_OUT_RECEIVES_WAREHOUSE_ID_FK"))
    private Warehouse warehouse;

    // Auto-generated per docs/TRANSACTIONS.md — see TransactionReferenceService.
    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

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

    @Column(nullable = false)
    private boolean voided = false;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by", length = 100)
    private String voidedBy;

    // Inert: nothing loads from a Pull Out Receive. Kept for parity with every other transaction type.
    @Column(nullable = false)
    private boolean loaded = false;

    // NATIVE for everything posted through the API; MIGRATED/RECONSTRUCTED only from the legacy migration loader.
    @ColumnDefault("'NATIVE'")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionOrigin origin = TransactionOrigin.NATIVE;

    @OneToMany(mappedBy = "pullOutReceive", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<PullOutReceiveLine> lines = new ArrayList<>();
}
