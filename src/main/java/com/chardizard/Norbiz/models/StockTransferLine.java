package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;

// Not Auditable: immutable once posted (see StockTransfer).
@Getter
@Setter
@Entity
@Table(name = "stock_transfer_lines")
public class StockTransferLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_transfer_id", nullable = false,
        foreignKey = @ForeignKey(name = "STOCK_TRANSFER_LINES_STOCK_TRANSFER_ID_FK"))
    private StockTransfer stockTransfer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "STOCK_TRANSFER_LINES_ITEM_ID_FK"))
    private Item item;

    // Selling price per unit — preloaded from the item's UNIT_PRICE when the request omits it.
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    // Set to quantity when a Delivery Receipt loads the transfer.
    @Column(name = "quantity_loaded", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityLoaded = BigDecimal.ZERO;
}
