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
// Pays a customer back unused credit (an advance or a credit memo): opens a RECEIVABLE CHARGE open item that the
// selected credits are fully applied to, and pays it with one issued instrument (legacy tblCustomerRefunds).
@Getter
@Setter
@Entity
@Table(
    name = "customer_refunds",
    uniqueConstraints = @UniqueConstraint(name = "CUSTOMER_REFUNDS_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class CustomerRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "CUSTOMER_REFUNDS_COMPANY_ID_FK"))
    private Company company;

    // Counterparty — see docs/TRANSACTIONS.md "Standard transaction document layout".
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false,
        foreignKey = @ForeignKey(name = "CUSTOMER_REFUNDS_CUSTOMER_ID_FK"))
    private Customer customer;

    // Auto-generated per docs/TRANSACTIONS.md — see TransactionReferenceService.
    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    // User-supplied control number from the physical source document, if any.
    @Column(name = "sheet_number", length = 100)
    private String sheetNumber;

    // Business-effective date, represented as the UTC-midnight instant of that day (see InventoryMovement).
    @Column(name = "refund_date", nullable = false)
    private Instant refundDate;

    @Column(name = "remarks", length = 255)
    private String remarks;

    // Sum of line amounts.
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private IssuedPaymentMode mode;

    // Our account it is drawn on / sent from (required unless CASH or OTHERS).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_account_id",
        foreignKey = @ForeignKey(name = "CUSTOMER_REFUNDS_BANK_ACCOUNT_ID_FK"))
    private BankAccount bankAccount;

    @Column(name = "check_number", length = 50)
    private String checkNumber;

    @Column(name = "check_date")
    private Instant checkDate;

    // The issued instrument's status (see docs/ACCOUNTING.md "Check lifecycles").
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IssuedCheckStatus status;

    @Column(name = "status_date")
    private Instant statusDate;

    // The Bank Reconciliation that cleared it (null when cleared by hand, or not cleared): voiding that
    // reconciliation puts it back to its previous status.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_reconciliation_id",
        foreignKey = @ForeignKey(name = "CUSTOMER_REFUNDS_BANK_RECONCILIATION_ID_FK"))
    private BankReconciliation bankReconciliation;

    // The RECEIVABLE CHARGE the refunded credits are applied to.
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "open_item_id", unique = true,
        foreignKey = @ForeignKey(name = "CUSTOMER_REFUNDS_OPEN_ITEM_ID_FK"))
    private OpenItem openItem;

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

    @OneToMany(mappedBy = "customerRefund", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<CustomerRefundLine> lines = new ArrayList<>();
}
