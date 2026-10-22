package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One receivable or payable in the AR/AP subledger: a document that raises a party's balance (CHARGE — Sales
 * Invoice, Purchase Invoice, debit memo, refund) or a source of credit that lowers it (CREDIT — payment tender,
 * credit memo). The amounts are a cache of the active {@link OpenItemAllocation}s, written only by
 * OpenItemService under a row lock (like InventoryBalance over InventoryMovement) — see docs/ACCOUNTING.md.
 *
 * Not Auditable: the allocation ledger is the audit trail.
 */
@Getter
@Setter
@Entity
@Table(
    name = "open_items",
    uniqueConstraints = @UniqueConstraint(name = "OPEN_ITEMS_SOURCE_UQ", columnNames = {"source_type", "source_id"}),
    indexes = {
        @Index(name = "OPEN_ITEMS_CUSTOMER_IDX", columnList = "company_id, customer_id, status"),
        @Index(name = "OPEN_ITEMS_SUPPLIER_IDX", columnList = "company_id, supplier_id, status")
    }
)
public class OpenItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "OPEN_ITEMS_COMPANY_ID_FK"))
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountSide side;

    // Exactly one of customer (RECEIVABLE) / supplier (PAYABLE) is set.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id",
        foreignKey = @ForeignKey(name = "OPEN_ITEMS_CUSTOMER_ID_FK"))
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id",
        foreignKey = @ForeignKey(name = "OPEN_ITEMS_SUPPLIER_ID_FK"))
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OpenItemDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 40)
    private OpenItemSourceType sourceType;

    // Id of the source document (or payment tender line) — polymorphic, so no FK.
    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    // Snapshot of the source document's reference number (a tender's is its payment's, plus the check number).
    @Column(name = "reference_number", nullable = false, length = 100)
    private String referenceNumber;

    // Business date of the source document (UTC-midnight instant, see InventoryMovement).
    @Column(name = "document_date", nullable = false)
    private Instant documentDate;

    // CHARGE items only: when payment is due (document date + terms). Drives aging.
    @Column(name = "due_date")
    private Instant dueDate;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // Sum of active allocations (as the credit or the charge side).
    @Column(name = "applied_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal appliedAmount = BigDecimal.ZERO;

    // CHARGE items: the part of appliedAmount paid by instruments that haven't cleared yet (legacy "PDC").
    @Column(name = "pending_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal pendingAmount = BigDecimal.ZERO;

    // CHARGE items: running total of payments reversed because their check bounced (informational).
    @Column(name = "bounced_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal bouncedAmount = BigDecimal.ZERO;

    // amount - appliedAmount (stored so lists can filter/sort on it).
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OpenItemStatus status = OpenItemStatus.OPEN;

    // VOIDED items: when and why. VOIDED = its document was voided (treated as never posted by reports); BOUNCED /
    // CANCELLED = its check bounced / was cancelled, so it stood until voidedDate (ledgers and as-of aging show that).
    @Column(name = "voided_date")
    private Instant voidedDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "void_reason", length = 20)
    private AllocationReversalReason voidReason;

    // CREDIT items: false while the paying instrument hasn't cleared — allocations from it count as pending.
    @Column(nullable = false)
    private boolean cleared = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;
}
