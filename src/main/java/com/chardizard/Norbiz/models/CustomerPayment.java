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
// Money received from a customer (legacy tblCustomerPayments): one or more tenders (cash, checks, transfers...), each
// opening a RECEIVABLE CREDIT open item, optionally applied to the customer's open charges right away. A tender's
// unapplied balance is an advance, applied later by a Customer Credit Application — see docs/ACCOUNTING.md.
@Getter
@Setter
@Entity
@Table(
    name = "customer_payments",
    uniqueConstraints = @UniqueConstraint(name = "CUSTOMER_PAYMENTS_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class CustomerPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "CUSTOMER_PAYMENTS_COMPANY_ID_FK"))
    private Company company;

    // Counterparty — see docs/TRANSACTIONS.md "Standard transaction document layout".
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false,
        foreignKey = @ForeignKey(name = "CUSTOMER_PAYMENTS_CUSTOMER_ID_FK"))
    private Customer customer;

    // Auto-generated per docs/TRANSACTIONS.md — see TransactionReferenceService.
    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    // User-supplied control number from the physical source document, if any.
    @Column(name = "sheet_number", length = 100)
    private String sheetNumber;

    // Business-effective date, represented as the UTC-midnight instant of that day (see InventoryMovement).
    @Column(name = "payment_date", nullable = false)
    private Instant paymentDate;

    @Column(name = "remarks", length = 255)
    private String remarks;

    // Sum of tender amounts.
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

    @OneToMany(mappedBy = "customerPayment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<CustomerPaymentTender> tenders = new ArrayList<>();
}
