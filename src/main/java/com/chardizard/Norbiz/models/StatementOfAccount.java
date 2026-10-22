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
// A customer's open items as of a date, frozen for printing and sending (legacy tblStatementOfAccounts), with the
// balance split into aging buckets by due date. No ledger effect.
@Getter
@Setter
@Entity
@Table(
    name = "statements_of_account",
    uniqueConstraints = @UniqueConstraint(name = "STATEMENTS_OF_ACCOUNT_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class StatementOfAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "STATEMENTS_OF_ACCOUNT_COMPANY_ID_FK"))
    private Company company;

    // Counterparty — see docs/TRANSACTIONS.md "Standard transaction document layout".
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false,
        foreignKey = @ForeignKey(name = "STATEMENTS_OF_ACCOUNT_CUSTOMER_ID_FK"))
    private Customer customer;

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

    // Open charges less unapplied credits.
    @Column(name = "total_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalBalance;

    // Not yet due.
    @Column(name = "current_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal currentAmount;

    @Column(name = "days1_to30", nullable = false, precision = 19, scale = 2)
    private BigDecimal days1To30;

    @Column(name = "days31_to60", nullable = false, precision = 19, scale = 2)
    private BigDecimal days31To60;

    @Column(name = "days61_to90", nullable = false, precision = 19, scale = 2)
    private BigDecimal days61To90;

    @Column(name = "days91_to120", nullable = false, precision = 19, scale = 2)
    private BigDecimal days91To120;

    @Column(name = "over120", nullable = false, precision = 19, scale = 2)
    private BigDecimal over120;

    // Unapplied credits (advances, credit memos) as a positive amount.
    @Column(name = "unapplied_credits", nullable = false, precision = 19, scale = 2)
    private BigDecimal unappliedCredits;

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

    @OneToMany(mappedBy = "statementOfAccount", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<StatementOfAccountLine> lines = new ArrayList<>();
}
