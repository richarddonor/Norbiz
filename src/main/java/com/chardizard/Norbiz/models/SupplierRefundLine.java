package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

// Not Auditable: immutable once posted (see SupplierRefund).
@Getter
@Setter
@Entity
@Table(
    name = "supplier_refund_lines"
)
public class SupplierRefundLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_refund_id", nullable = false,
        foreignKey = @ForeignKey(name = "SUPPLIER_REFUND_LINES_SUPPLIER_REFUND_ID_FK"))
    private SupplierRefund supplierRefund;

    // The CREDIT open item refunded.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credit_item_id", nullable = false,
        foreignKey = @ForeignKey(name = "SUPPLIER_REFUND_LINES_CREDIT_ITEM_ID_FK"))
    private OpenItem creditItem;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // Server-assigned, 1-based, in entry order.
    @Column(name = "line_number", nullable = false)
    private int lineNumber;
}
