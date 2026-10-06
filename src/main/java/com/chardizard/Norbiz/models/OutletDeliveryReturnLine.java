package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// Not Auditable: immutable once posted (see OutletDeliveryReturn).
@Getter
@Setter
@Entity
@Table(name = "outlet_delivery_return_lines")
public class OutletDeliveryReturnLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_delivery_return_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_DELIVERY_RETURN_LINES_OUTLET_DELIVERY_RETURN_ID_FK"))
    private OutletDeliveryReturn outletDeliveryReturn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_DELIVERY_RETURN_LINES_ITEM_ID_FK"))
    private Item item;

    // The Outlet Delivery Receipt line this amount was returned against (a request line may split across several).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_delivery_receipt_line_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_DELIVERY_RETURN_LINES_OUTLET_DELIVERY_RECEIPT_LINE_ID_FK"))
    private OutletDeliveryReceiptLine outletDeliveryReceiptLine;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    // Copied from the sold line — the value being refunded (and the commission base being reversed).
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    // Inert: nothing loads from an Outlet Delivery Return.
    @Column(name = "quantity_loaded", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityLoaded = BigDecimal.ZERO;
}
