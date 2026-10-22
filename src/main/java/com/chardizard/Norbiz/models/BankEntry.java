package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One movement in a company bank account's book (the bank-side ledger): a deposit slip, a transfer received,
 * a check we issued, a returned (bounced) check, or a bank charge/interest booked by a reconciliation. Signed:
 * money in is positive, money out negative. Book balance = account opening balance + sum of non-voided entries.
 *
 * Append-only except for the clearing stamp a Bank Reconciliation sets, and {@code voided} when the source
 * document is voided (blocked once reconciled). Not Auditable: it is itself the trail.
 */
@Getter
@Setter
@Entity
@Table(
    name = "bank_entries",
    indexes = {
        @Index(name = "BANK_ENTRIES_ACCOUNT_IDX", columnList = "bank_account_id, entry_date"),
        @Index(name = "BANK_ENTRIES_SOURCE_IDX", columnList = "source_type, source_id")
    }
)
public class BankEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "BANK_ENTRIES_COMPANY_ID_FK"))
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bank_account_id", nullable = false,
        foreignKey = @ForeignKey(name = "BANK_ENTRIES_BANK_ACCOUNT_ID_FK"))
    private BankAccount bankAccount;

    @Column(name = "entry_date", nullable = false)
    private Instant entryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 30)
    private BankEntryType entryType;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // The transaction that posted it, and the tender line within it when there is one (issued/returned checks).
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 40)
    private TransactionType sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "source_line_id")
    private Long sourceLineId;

    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    @Column(name = "check_number", length = 50)
    private String checkNumber;

    @Column(name = "check_date")
    private Instant checkDate;

    // Payee / payer name snapshot, for the bank book and reconciliation screens.
    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private boolean cleared = false;

    @Column(name = "cleared_date")
    private Instant clearedDate;

    // The reconciliation that cleared it (null until then).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_reconciliation_id",
        foreignKey = @ForeignKey(name = "BANK_ENTRIES_BANK_RECONCILIATION_ID_FK"))
    private BankReconciliation bankReconciliation;

    @Column(nullable = false)
    private boolean voided = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;
}
