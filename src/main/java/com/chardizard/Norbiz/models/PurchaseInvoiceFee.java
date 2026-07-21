package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// Additional payable entries beyond line items (e.g. freight, handling) — see docs/TRANSACTIONS.md "Purchase Invoice".
// Not Auditable: immutable once posted, same as PurchaseInvoice itself.
@Getter
@Setter
@Entity
@Table(name = "purchase_invoice_fees")
public class PurchaseInvoiceFee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_invoice_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_INVOICE_FEES_PURCHASE_INVOICE_ID_FK"))
    private PurchaseInvoice purchaseInvoice;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
}
