package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// Not Auditable: purchase invoice lines are immutable once posted (see PurchaseInvoice),
// so there is nothing to diff/track — the ledger (InventoryMovement) is the audit trail.
@Getter
@Setter
@Entity
@Table(name = "purchase_invoice_lines")
public class PurchaseInvoiceLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_invoice_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_INVOICE_LINES_PURCHASE_INVOICE_ID_FK"))
    private PurchaseInvoice purchaseInvoice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_INVOICE_LINES_ITEM_ID_FK"))
    private Item item;

    // Null for Direct-mode lines. Set for PO-based lines, copied verbatim from the originating PO line.
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "purchase_order_line_id",
        foreignKey = @ForeignKey(name = "PURCHASE_INVOICE_LINES_PURCHASE_ORDER_LINE_ID_FK"))
    private PurchaseOrderLine purchaseOrderLine;

    // PO-based mode: copied verbatim from the PO line (full 1:1 load). Direct mode: user-supplied.
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    // Sensitive — gated by the existing VIEW_COST_PRICE permission, same as PurchaseOrderLine.costPrice.
    @Column(name = "cost_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal costPrice = BigDecimal.ZERO;

    // Per-line discount percentage (0-100) — see docs/TRANSACTIONS.md "Purchase Invoice".
    @Column(name = "discount_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercentage = BigDecimal.ZERO;

    // Inert unless this line belongs to a Direct-mode invoice — see PurchaseInvoice.loaded.
    @Column(name = "quantity_loaded", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityLoaded = BigDecimal.ZERO;
}
