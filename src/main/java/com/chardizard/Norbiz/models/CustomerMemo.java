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
// A customer debit or credit memo. DEBIT raises what the customer owes us (RECEIVABLE CHARGE); CREDIT lowers it (RECEIVABLE CREDIT, e.g. a sales return or rebate).
// A CREDIT-side memo may be applied to open charges right away (inline applications). OPENING_BALANCE memos carry
// balances from before the accounting start date — see docs/ACCOUNTING.md "Memos".
@Getter
@Setter
@Entity
@Table(
    name = "customer_memos",
    uniqueConstraints = @UniqueConstraint(name = "CUSTOMER_MEMOS_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class CustomerMemo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "CUSTOMER_MEMOS_COMPANY_ID_FK"))
    private Company company;

    // Counterparty — see docs/TRANSACTIONS.md "Standard transaction document layout".
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false,
        foreignKey = @ForeignKey(name = "CUSTOMER_MEMOS_CUSTOMER_ID_FK"))
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(name = "memo_type", nullable = false, length = 10)
    private MemoType memoType;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private CustomerMemoReason reason;

    // Auto-generated per docs/TRANSACTIONS.md — see TransactionReferenceService.
    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    // User-supplied control number from the physical source document, if any.
    @Column(name = "sheet_number", length = 100)
    private String sheetNumber;

    // Business-effective date, represented as the UTC-midnight instant of that day (see InventoryMovement).
    @Column(name = "memo_date", nullable = false)
    private Instant memoDate;

    @Column(name = "remarks", length = 255)
    private String remarks;

    // Charge-side memos only: payment terms in days.
    @Column(name = "term_days", nullable = false)
    private int termDays = 0;

    // Charge-side memos only: memoDate + termDays unless overridden.
    @Column(name = "due_date")
    private Instant dueDate;

    // OPENING_BALANCE only: the legacy document number the balance came from.
    @Column(name = "original_reference", length = 100)
    private String originalReference;

    // Sum of line amounts.
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // The open item this memo opened (CHARGE or CREDIT by side and memo type).
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "open_item_id", unique = true,
        foreignKey = @ForeignKey(name = "CUSTOMER_MEMOS_OPEN_ITEM_ID_FK"))
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

    @OneToMany(mappedBy = "customerMemo", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<CustomerMemoLine> lines = new ArrayList<>();
}
