package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

// Snapshot of what a Bank Reconciliation cleared/booked, for its printout and Detailed report.
@Getter
@Setter
@Entity
@Table(
    name = "bank_reconciliation_lines"
)
public class BankReconciliationLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bank_reconciliation_id", nullable = false,
        foreignKey = @ForeignKey(name = "BANK_RECONCILIATION_LINES_BANK_RECONCILIATION_ID_FK"))
    private BankReconciliation bankReconciliation;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private BankReconciliationLineKind kind;

    // The cleared entry, or the entry booked for an adjustment.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bank_entry_id", nullable = false,
        foreignKey = @ForeignKey(name = "BANK_RECONCILIATION_LINES_BANK_ENTRY_ID_FK"))
    private BankEntry bankEntry;

    // ADJUSTMENT lines only.
    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", length = 20)
    private BankAdjustmentType adjustmentType;

    @Column(name = "entry_date", nullable = false)
    private Instant entryDate;

    // Snapshot of the entry's source reference.
    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    @Column(name = "check_number", length = 50)
    private String checkNumber;

    @Column(name = "description", length = 255)
    private String description;

    // Signed, as on the bank entry.
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // Server-assigned, 1-based, in entry order.
    @Column(name = "line_number", nullable = false)
    private int lineNumber;
}
