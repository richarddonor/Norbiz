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
// Money paid to a supplier (legacy tblSupplierPayments): one or more tenders (checks drawn on our accounts, transfers,
// cash), each opening a PAYABLE CREDIT open item, optionally applied to the supplier's open charges right away.
// A tender's unapplied balance is an advance to the supplier — see docs/ACCOUNTING.md.
@Getter
@Setter
@Entity
@Table(
    name = "supplier_payments",
    uniqueConstraints = @UniqueConstraint(name = "SUPPLIER_PAYMENTS_COMPANY_REFERENCE_UQ", columnNames = {"company_id", "reference_number"})
)
public class SupplierPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "SUPPLIER_PAYMENTS_COMPANY_ID_FK"))
    private Company company;

    // Counterparty — see docs/TRANSACTIONS.md "Standard transaction document layout".
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false,
        foreignKey = @ForeignKey(name = "SUPPLIER_PAYMENTS_SUPPLIER_ID_FK"))
    private Supplier supplier;

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

    // Disbursement voucher number, if any.
    @Column(name = "voucher_number", length = 50)
    private String voucherNumber;

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

    @OneToMany(mappedBy = "supplierPayment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<SupplierPaymentTender> tenders = new ArrayList<>();
}
