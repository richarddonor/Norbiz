package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;

// Not Auditable: immutable once posted (see OutletPullOut).
@Getter
@Setter
@Entity
@Table(name = "outlet_pull_out_lines")
public class OutletPullOutLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_pull_out_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_PULL_OUT_LINES_OUTLET_PULL_OUT_ID_FK"))
    private OutletPullOut outletPullOut;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "OUTLET_PULL_OUT_LINES_ITEM_ID_FK"))
    private Item item;

    // Selling price per unit — preloaded from the item's UNIT_PRICE when the request omits it.
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    // Amount already received by Pull Out Receive(s).
    @Column(name = "quantity_loaded", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityLoaded = BigDecimal.ZERO;
}
