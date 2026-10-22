package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Applies part of a CREDIT open item (payment tender, credit memo) to a CHARGE open item (invoice, debit memo)
 * of the same party. Append-only except for the reversal stamp: voiding the document that made it, bouncing
 * the check that funded it, or cancelling the issued check reverses it instead of deleting it, so balances can
 * be rebuilt as of any date (aging, ledgers). OpenItem amounts are a cache of the active rows.
 */
@Getter
@Setter
@Entity
@Table(
    name = "open_item_allocations",
    indexes = {
        @Index(name = "OPEN_ITEM_ALLOCATIONS_CREDIT_IDX", columnList = "credit_item_id"),
        @Index(name = "OPEN_ITEM_ALLOCATIONS_CHARGE_IDX", columnList = "charge_item_id"),
        @Index(name = "OPEN_ITEM_ALLOCATIONS_SOURCE_IDX", columnList = "source_type, source_id")
    }
)
public class OpenItemAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "OPEN_ITEM_ALLOCATIONS_COMPANY_ID_FK"))
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credit_item_id", nullable = false,
        foreignKey = @ForeignKey(name = "OPEN_ITEM_ALLOCATIONS_CREDIT_ITEM_ID_FK"))
    private OpenItem creditItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "charge_item_id", nullable = false,
        foreignKey = @ForeignKey(name = "OPEN_ITEM_ALLOCATIONS_CHARGE_ITEM_ID_FK"))
    private OpenItem chargeItem;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // Business date the application takes effect (the posting document's date).
    @Column(name = "allocation_date", nullable = false)
    private Instant allocationDate;

    // The transaction that made the allocation (payment, memo, credit application, refund).
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 40)
    private TransactionType sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    // True while the credit item's instrument hasn't cleared (counted in the charge item's pendingAmount).
    @Column(nullable = false)
    private boolean pending = false;

    @Column(nullable = false)
    private boolean reversed = false;

    @Column(name = "reversed_date")
    private Instant reversedDate;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "reversed_by", length = 100)
    private String reversedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "reversal_reason", length = 20)
    private AllocationReversalReason reversalReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;
}
