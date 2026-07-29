package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// Not Auditable: purchase receive lines are immutable once posted (see PurchaseReceive),
// so there is nothing to diff/track — the ledger (InventoryMovement) is the audit trail.
@Getter
@Setter
@Entity
@Table(name = "purchase_receive_lines")
public class PurchaseReceiveLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_receive_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_RECEIVE_LINES_PURCHASE_RECEIVE_ID_FK"))
    private PurchaseReceive purchaseReceive;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_RECEIVE_LINES_ITEM_ID_FK"))
    private Item item;

    // Set when the receive's source is a Purchase Order.
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "purchase_order_line_id",
        foreignKey = @ForeignKey(name = "PURCHASE_RECEIVE_LINES_PURCHASE_ORDER_LINE_ID_FK"))
    private PurchaseOrderLine purchaseOrderLine;

    // Set when the receive's source is a Direct-mode Purchase Invoice.
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "purchase_invoice_line_id",
        foreignKey = @ForeignKey(name = "PURCHASE_RECEIVE_LINES_PURCHASE_INVOICE_LINE_ID_FK"))
    private PurchaseInvoiceLine purchaseInvoiceLine;

    // Quantity received in this specific receive transaction — may be a partial slice of the
    // source line's outstanding (quantity - quantityLoaded) amount. See PurchaseReceiveService.
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    // Inert: nothing currently loads from a Purchase Receive, same as InventoryAdjustmentLine.
    @Column(name = "quantity_loaded", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityLoaded = BigDecimal.ZERO;
}
