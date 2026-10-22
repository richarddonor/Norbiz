package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

// Not Auditable: immutable once posted (see CustomerRefund).
@Getter
@Setter
@Entity
@Table(
    name = "customer_refund_lines"
)
public class CustomerRefundLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_refund_id", nullable = false,
        foreignKey = @ForeignKey(name = "CUSTOMER_REFUND_LINES_CUSTOMER_REFUND_ID_FK"))
    private CustomerRefund customerRefund;

    // The CREDIT open item refunded.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credit_item_id", nullable = false,
        foreignKey = @ForeignKey(name = "CUSTOMER_REFUND_LINES_CREDIT_ITEM_ID_FK"))
    private OpenItem creditItem;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // Server-assigned, 1-based, in entry order.
    @Column(name = "line_number", nullable = false)
    private int lineNumber;
}
