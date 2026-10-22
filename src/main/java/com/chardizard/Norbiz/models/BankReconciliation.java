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
// Reconciles one bank account to a bank statement (legacy tblBankReconciliations): marks the book entries the bank
// has cleared (deposits, our checks, transfers, returned checks) and books bank-only items (charges, interest).
// previousBalance + clearedTotal + adjustmentsTotal must equal the statement's ending balance. Only the latest
// reconciliation of an account can be voided.
@Getter
@Setter
@Entity
@Table(
    name = "bank_reconciliations",
    uniqueConstraints = @UniqueConstraint(name = "BANK_RECONCILIATIONS_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class BankReconciliation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "BANK_RECONCILIATIONS_COMPANY_ID_FK"))
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bank_account_id", nullable = false,
        foreignKey = @ForeignKey(name = "BANK_RECONCILIATIONS_BANK_ACCOUNT_ID_FK"))
    private BankAccount bankAccount;

    // Auto-generated per docs/TRANSACTIONS.md — see TransactionReferenceService.
    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    // User-supplied control number from the physical source document, if any.
    @Column(name = "sheet_number", length = 100)
    private String sheetNumber;

    // Business-effective date, represented as the UTC-midnight instant of that day (see InventoryMovement).
    @Column(name = "statement_date", nullable = false)
    private Instant statementDate;

    @Column(name = "remarks", length = 255)
    private String remarks;

    // Ending balance of the previous reconciliation (else the account's opening balance).
    @Column(name = "previous_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal previousBalance;

    // Ending balance per the bank statement.
    @Column(name = "statement_ending_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal statementEndingBalance;

    // Sum of the cleared book entries.
    @Column(name = "cleared_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal clearedTotal;

    // Sum of the bank-only adjustments booked.
    @Column(name = "adjustments_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal adjustmentsTotal;

    // Book balance as of statementDate after this reconciliation.
    @Column(name = "book_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal bookBalance;

    // Book entries dated on/before statementDate still uncleared (deposits in transit, outstanding checks).
    @Column(name = "outstanding_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal outstandingTotal;

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

    @OneToMany(mappedBy = "bankReconciliation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<BankReconciliationLine> lines = new ArrayList<>();
}
