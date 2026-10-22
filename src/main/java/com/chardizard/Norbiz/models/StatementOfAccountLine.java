package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

// Snapshot of one open item on a Statement of Account.
@Getter
@Setter
@Entity
@Table(
    name = "statement_of_account_lines"
)
public class StatementOfAccountLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "statement_of_account_id", nullable = false,
        foreignKey = @ForeignKey(name = "STATEMENT_OF_ACCOUNT_LINES_STATEMENT_OF_ACCOUNT_ID_FK"))
    private StatementOfAccount statementOfAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "open_item_id", nullable = false,
        foreignKey = @ForeignKey(name = "STATEMENT_OF_ACCOUNT_LINES_OPEN_ITEM_ID_FK"))
    private OpenItem openItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 40)
    private OpenItemSourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 20)
    private OpenItemDirection direction;

    @Column(name = "reference_number", nullable = false, length = 100)
    private String referenceNumber;

    @Column(name = "document_date", nullable = false)
    private Instant documentDate;

    @Column(name = "due_date")
    private Instant dueDate;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // Applied as of the statement date.
    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidAmount;

    // Part of paidAmount still in uncleared checks.
    @Column(name = "pending_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal pendingAmount;

    // As of the statement date; negative for unapplied credits.
    @Column(name = "balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    // Days past due as of the statement date (0 when not yet due).
    @Column(name = "days_overdue", nullable = false)
    private int daysOverdue;

    // Server-assigned, 1-based, in entry order.
    @Column(name = "line_number", nullable = false)
    private int lineNumber;
}
