package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

// Not Auditable: immutable once posted (see SalesInvoice).
@Getter
@Setter
@Entity
@Table(
    name = "sales_invoice_lines"
)
public class SalesInvoiceLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sales_invoice_id", nullable = false,
        foreignKey = @ForeignKey(name = "SALES_INVOICE_LINES_SALES_INVOICE_ID_FK"))
    private SalesInvoice salesInvoice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "SALES_INVOICE_LINES_ITEM_ID_FK"))
    private Item item;

    // The billed DR line (DR-based invoices only).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_receipt_line_id",
        foreignKey = @ForeignKey(name = "SALES_INVOICE_LINES_DELIVERY_RECEIPT_LINE_ID_FK"))
    private DeliveryReceiptLine deliveryReceiptLine;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    // Selling price per unit — preloaded from the item's UNIT_PRICE when the request omits it.
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    // Line discount percentage (0-100).
    @Column(name = "discount_percentage", nullable = false, precision = 19, scale = 2)
    private BigDecimal discountPercentage = BigDecimal.ZERO;

    // Server-assigned, 1-based, in entry order.
    @Column(name = "line_number", nullable = false)
    private int lineNumber;
}
