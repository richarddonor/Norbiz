package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// Not Auditable: immutable once posted (see OutletReceive).
@Getter
@Setter
@Entity
@Table(name = "outlet_receive_lines")
public class OutletReceiveLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_receive_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_RECEIVE_LINES_OUTLET_RECEIVE_ID_FK"))
    private OutletReceive outletReceive;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_RECEIVE_LINES_ITEM_ID_FK"))
    private Item item;

    // The Delivery Receipt line this amount was received against (a request line may split across several).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_receipt_line_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_RECEIVE_LINES_DELIVERY_RECEIPT_LINE_ID_FK"))
    private DeliveryReceiptLine deliveryReceiptLine;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    // Inert: nothing loads from an Outlet Receive.
    @Column(name = "quantity_loaded", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityLoaded = BigDecimal.ZERO;
}
