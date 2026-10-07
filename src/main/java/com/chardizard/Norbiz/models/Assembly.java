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
// Builds finished items in the main warehouse: OUTPUT lines add on-hand stock, MATERIAL lines (the raw
// materials consumed) deduct it. Both kinds live in one line table so the ledger and detailed report stay uniform.
@Getter
@Setter
@Entity
@Table(
    name = "assemblies",
    uniqueConstraints = @UniqueConstraint(name = "ASSEMBLIES_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class Assembly {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "ASSEMBLIES_COMPANY_ID_FK"))
    private Company company;

    // The company's main warehouse at posting time (snapshot).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false,
        foreignKey = @ForeignKey(name = "ASSEMBLIES_WAREHOUSE_ID_FK"))
    private Warehouse warehouse;

    // Auto-generated per docs/TRANSACTIONS.md — see TransactionReferenceService.
    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    @Column(name = "sheet_number", length = 100)
    private String sheetNumber;

    // Business-effective date, represented as the UTC-midnight instant of that day (see InventoryMovement).
    @Column(name = "assembly_date", nullable = false)
    private Instant assemblyDate;

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

    // Inert: nothing loads from an Assembly. Kept for parity with every other transaction type.
    @Column(nullable = false)
    private boolean loaded = false;

    // NATIVE for everything posted through the API; MIGRATED/RECONSTRUCTED only from the legacy migration loader.
    @ColumnDefault("'NATIVE'")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionOrigin origin = TransactionOrigin.NATIVE;

    @OneToMany(mappedBy = "assembly", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<AssemblyLine> lines = new ArrayList<>();
}
