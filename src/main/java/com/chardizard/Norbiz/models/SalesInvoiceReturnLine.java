package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

// Not Auditable: immutable once posted (see SalesInvoiceReturn).
@Getter
@Setter
@Entity
@Table(name = "sales_invoice_return_lines")
public class SalesInvoiceReturnLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sales_invoice_return_id", nullable = false,
        foreignKey = @ForeignKey(name = "SALES_INVOICE_RETURN_LINES_SALES_INVOICE_RETURN_ID_FK"))
    private SalesInvoiceReturn salesInvoiceReturn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "SALES_INVOICE_RETURN_LINES_ITEM_ID_FK"))
    private Item item;

    // The invoice line this quantity was returned against. Required by the API; nullable only for migrated legacy
    // return lines that named no invoice (docs/LEGACY_MIGRATION.md).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_invoice_line_id",
        foreignKey = @ForeignKey(name = "SALES_INVOICE_RETURN_LINES_SALES_INVOICE_LINE_ID_FK"))
    private SalesInvoiceLine salesInvoiceLine;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    // The invoice line's net price per unit (its line and header discounts folded in) — the value credited back.
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    // Server-assigned, 1-based, in entry order.
    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    // Whether this line moved stock: always for native returns; migrated lines only when legacy posted them.
    // ColumnDefault: lets ddl-auto=update add the NOT NULL column to existing rows.
    @ColumnDefault("true")
    @Column(name = "posts_stock", nullable = false)
    private boolean postsStock = true;
}
