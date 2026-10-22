package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

// Not Auditable: immutable once posted (see CustomerMemo).
@Getter
@Setter
@Entity
@Table(
    name = "customer_memo_lines"
)
public class CustomerMemoLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_memo_id", nullable = false,
        foreignKey = @ForeignKey(name = "CUSTOMER_MEMO_LINES_CUSTOMER_MEMO_ID_FK"))
    private CustomerMemo customerMemo;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // Server-assigned, 1-based, in entry order.
    @Column(name = "line_number", nullable = false)
    private int lineNumber;
}
