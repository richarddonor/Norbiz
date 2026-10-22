package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

// Not Auditable: immutable once posted (only create/view/void) — the open-item allocation ledger is the audit trail.
// Applies supplier credits posted earlier (unapplied payment tenders = advances, credit-side memos) to open charges.
// Each line is one OpenItemAllocation; voiding reverses them.
@Getter
@Setter
@Entity
@Table(
    name = "supplier_credit_applications",
    uniqueConstraints = @UniqueConstraint(name = "SUPPLIER_CREDIT_APPLICATIONS_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class SupplierCreditApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "SUPPLIER_CREDIT_APPLICATIONS_COMPANY_ID_FK"))
    private Company company;

    // Counterparty — see docs/TRANSACTIONS.md "Standard transaction document layout".
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false,
        foreignKey = @ForeignKey(name = "SUPPLIER_CREDIT_APPLICATIONS_SUPPLIER_ID_FK"))
    private Supplier supplier;

    // Auto-generated per docs/TRANSACTIONS.md — see TransactionReferenceService.
    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    // User-supplied control number from the physical source document, if any.
    @Column(name = "sheet_number", length = 100)
    private String sheetNumber;

    // Business-effective date, represented as the UTC-midnight instant of that day (see InventoryMovement).
    @Column(name = "application_date", nullable = false)
    private Instant applicationDate;

    @Column(name = "remarks", length = 255)
    private String remarks;

    // Sum of line amounts.
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    // Voiding is the only sanctioned way to cancel an immutable transaction — see docs/TRANSACTIONS.md "Voiding".
    @Column(name = "voided", nullable = false)
    private boolean voided = false;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by", length = 100)
    private String voidedBy;

    // NATIVE for everything posted through the API.
    // ColumnDefault: lets ddl-auto=update add the NOT NULL column to existing rows.
    @ColumnDefault("'NATIVE'")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionOrigin origin = TransactionOrigin.NATIVE;

    @OneToMany(mappedBy = "supplierCreditApplication", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<SupplierCreditApplicationLine> lines = new ArrayList<>();
}
