package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// Not Auditable: immutable once posted (see OutletDeliveryReceipt).
@Getter
@Setter
@Entity
@Table(name = "outlet_delivery_receipt_lines")
public class OutletDeliveryReceiptLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_delivery_receipt_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_DELIVERY_RECEIPT_LINES_OUTLET_DELIVERY_RECEIPT_ID_FK"))
    private OutletDeliveryReceipt outletDeliveryReceipt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_DELIVERY_RECEIPT_LINES_ITEM_ID_FK"))
    private Item item;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    // Selling price per unit — preloaded from the item's UNIT_PRICE when the request omits it.
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    // Amount already returned by Outlet Delivery Return(s).
    @Column(name = "quantity_loaded", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityLoaded = BigDecimal.ZERO;
}
