package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

// One means of payment within a Customer Payment (legacy tblCustomerPaymentDetails). Not Auditable: status changes are
// recorded as STATUS_CHANGED transaction events on the payment.
@Getter
@Setter
@Entity
@Table(
    name = "customer_payment_tenders"
)
public class CustomerPaymentTender {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_payment_id", nullable = false,
        foreignKey = @ForeignKey(name = "CUSTOMER_PAYMENT_TENDERS_CUSTOMER_PAYMENT_ID_FK"))
    private CustomerPayment customerPayment;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private ReceivedPaymentMode mode;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // CHECK: the check number; BANK_TRANSFER/CREDIT_CARD: the transaction reference.
    @Column(name = "check_number", length = 50)
    private String checkNumber;

    // CHECK only: the date on the check (a later date = post-dated).
    @Column(name = "check_date")
    private Instant checkDate;

    // CHECK only: the drawee bank.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_id",
        foreignKey = @ForeignKey(name = "CUSTOMER_PAYMENT_TENDERS_BANK_ID_FK"))
    private Bank bank;

    // BANK_TRANSFER/CREDIT_CARD: our account it landed in. CASH/CHECK: set when deposited.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deposit_bank_account_id",
        foreignKey = @ForeignKey(name = "CUSTOMER_PAYMENT_TENDERS_DEPOSIT_BANK_ACCOUNT_ID_FK"))
    private BankAccount depositBankAccount;

    // OR number issued for this tender, if any.
    @Column(name = "official_receipt_number", length = 50)
    private String officialReceiptNumber;

    // PR number issued for this tender, if any.
    @Column(name = "provisional_receipt_number", length = 50)
    private String provisionalReceiptNumber;

    // See docs/ACCOUNTING.md "Check lifecycles".
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReceivedCheckStatus status;

    // Business date of the last status change.
    @Column(name = "status_date")
    private Instant statusDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "bounce_reason", length = 30)
    private BounceReason bounceReason;

    // The deposit slip that took it to the bank (CASH/CHECK).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deposit_id",
        foreignKey = @ForeignKey(name = "CUSTOMER_PAYMENT_TENDERS_DEPOSIT_ID_FK"))
    private Deposit deposit;

    // The Bank Reconciliation that cleared it (null when cleared by hand, or not cleared): voiding that
    // reconciliation puts it back to its previous status.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_reconciliation_id",
        foreignKey = @ForeignKey(name = "CUSTOMER_PAYMENT_TENDERS_BANK_RECONCILIATION_ID_FK"))
    private BankReconciliation bankReconciliation;

    // The bounced tender this one replaces, if any (legacy ParentID).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaces_tender_id",
        foreignKey = @ForeignKey(name = "CUSTOMER_PAYMENT_TENDERS_REPLACES_TENDER_ID_FK"))
    private CustomerPaymentTender replacesTender;

    // BOUNCED tenders: how much later replacement tenders have covered.
    @Column(name = "replaced_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal replacedAmount = BigDecimal.ZERO;

    // The RECEIVABLE CREDIT this tender opened.
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "open_item_id", unique = true,
        foreignKey = @ForeignKey(name = "CUSTOMER_PAYMENT_TENDERS_OPEN_ITEM_ID_FK"))
    private OpenItem openItem;

    // Server-assigned, 1-based, in entry order.
    @Column(name = "line_number", nullable = false)
    private int lineNumber;
}
