package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

// One means of payment within a Supplier Payment (legacy tblSupplierPaymentDetails). Not Auditable: status changes are
// recorded as STATUS_CHANGED transaction events on the payment.
@Getter
@Setter
@Entity
@Table(
    name = "supplier_payment_tenders"
)
public class SupplierPaymentTender {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_payment_id", nullable = false,
        foreignKey = @ForeignKey(name = "SUPPLIER_PAYMENT_TENDERS_SUPPLIER_PAYMENT_ID_FK"))
    private SupplierPayment supplierPayment;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private IssuedPaymentMode mode;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // Our account it is drawn on / sent from (required unless CASH or OTHERS).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_account_id",
        foreignKey = @ForeignKey(name = "SUPPLIER_PAYMENT_TENDERS_BANK_ACCOUNT_ID_FK"))
    private BankAccount bankAccount;

    // CHECK: the check number; BANK_TRANSFER: the transaction reference.
    @Column(name = "check_number", length = 50)
    private String checkNumber;

    // CHECK only: the date on the check (a later date = post-dated).
    @Column(name = "check_date")
    private Instant checkDate;

    // See docs/ACCOUNTING.md "Check lifecycles".
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IssuedCheckStatus status;

    // Business date of the last status change.
    @Column(name = "status_date")
    private Instant statusDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 30)
    private IssuedCancelReason cancelReason;

    // The Bank Reconciliation that cleared it (null when cleared by hand, or not cleared): voiding that
    // reconciliation puts it back to its previous status.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_reconciliation_id",
        foreignKey = @ForeignKey(name = "SUPPLIER_PAYMENT_TENDERS_BANK_RECONCILIATION_ID_FK"))
    private BankReconciliation bankReconciliation;

    // The cancelled tender this one replaces, if any.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaces_tender_id",
        foreignKey = @ForeignKey(name = "SUPPLIER_PAYMENT_TENDERS_REPLACES_TENDER_ID_FK"))
    private SupplierPaymentTender replacesTender;

    // The PAYABLE CREDIT this tender opened.
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "open_item_id", unique = true,
        foreignKey = @ForeignKey(name = "SUPPLIER_PAYMENT_TENDERS_OPEN_ITEM_ID_FK"))
    private OpenItem openItem;

    // Server-assigned, 1-based, in entry order.
    @Column(name = "line_number", nullable = false)
    private int lineNumber;
}
