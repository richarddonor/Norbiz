package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

// One existing credit (supplier debit memo, earlier advance) a Supplier Payment used, as posted: how much of it went to which
// charge. Numbered after the payment's tenders. Immutable, like the payment; the allocation it made is on the ledger
// (source = the payment), and voiding the payment reverses that allocation but keeps this line.
@Getter
@Setter
@Entity
@Table(
    name = "supplier_payment_credits"
)
public class SupplierPaymentCredit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_payment_id", nullable = false,
        foreignKey = @ForeignKey(name = "SUPPLIER_PAYMENT_CREDITS_SUPPLIER_PAYMENT_ID_FK"))
    private SupplierPayment supplierPayment;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    // The CREDIT open item used.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credit_item_id", nullable = false,
        foreignKey = @ForeignKey(name = "SUPPLIER_PAYMENT_CREDITS_CREDIT_ITEM_ID_FK"))
    private OpenItem creditItem;

    // The CHARGE open item it was applied to.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "charge_item_id", nullable = false,
        foreignKey = @ForeignKey(name = "SUPPLIER_PAYMENT_CREDITS_CHARGE_ITEM_ID_FK"))
    private OpenItem chargeItem;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
}
