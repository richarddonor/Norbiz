package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Not Auditable: adjustments are immutable once posted (no update/delete endpoint),
// so there is nothing to diff/track over time.
@Getter
@Setter
@Entity
@Table(
    name = "inventory_adjustments",
    uniqueConstraints = @UniqueConstraint(name = "INVENTORY_ADJUSTMENTS_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class InventoryAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "INVENTORY_ADJUSTMENTS_COMPANY_ID_FK"))
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false,
        foreignKey = @ForeignKey(name = "INVENTORY_ADJUSTMENTS_WAREHOUSE_ID_FK"))
    private Warehouse warehouse;

    // Auto-generated per CLAUDE.md's Transactions rule — see TransactionReferenceService.
    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    // User-supplied control number from the physical source document, if any.
    @Column(name = "sheet_number", length = 100)
    private String sheetNumber;

    // Business-effective date, represented as the UTC-midnight instant of that day (see InventoryMovement).
    @Column(name = "adjustment_date", nullable = false)
    private Instant adjustmentDate;

    @Column(length = 255)
    private String reason;

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

    // Nothing currently loads *from* an Inventory Adjustment (only Purchase Order -> future Purchase Receive
    // is documented) — this field is inert for now, kept for parity with docs/TRANSACTIONS.md "Transaction Loading"
    // so the void-blocks-on-loaded check is uniform across every transaction type.
    @Column(nullable = false)
    private boolean loaded = false;

    // NATIVE for everything posted through the API; MIGRATED/RECONSTRUCTED only from the legacy migration loader.
    // ColumnDefault: lets ddl-auto=update add the NOT NULL column to existing rows.
    @ColumnDefault("'NATIVE'")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionOrigin origin = TransactionOrigin.NATIVE;

    @OneToMany(mappedBy = "adjustment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<InventoryAdjustmentLine> lines = new ArrayList<>();
}
