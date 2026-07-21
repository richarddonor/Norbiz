package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// Not Auditable: purchase order lines are immutable once posted (see PurchaseOrder),
// so there is nothing to diff/track — the ledger (InventoryMovement) is the audit trail.
@Getter
@Setter
@Entity
@Table(name = "purchase_order_lines")
public class PurchaseOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_ORDER_LINES_PURCHASE_ORDER_ID_FK"))
    private PurchaseOrder purchaseOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "PURCHASE_ORDER_LINES_ITEM_ID_FK"))
    private Item item;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    // Sensitive — gated by the existing VIEW_COST_PRICE permission, same as Item.costPrice.
    @Column(name = "cost_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal costPrice = BigDecimal.ZERO;

    // Inert until the future Purchase Receive transaction exists — see PurchaseOrder.loaded.
    @Column(name = "quantity_loaded", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityLoaded = BigDecimal.ZERO;
}
