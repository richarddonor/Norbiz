package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

// Not Auditable: immutable once posted (see Deposit).
@Getter
@Setter
@Entity
@Table(
    name = "deposit_lines"
)
public class DepositLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "deposit_id", nullable = false,
        foreignKey = @ForeignKey(name = "DEPOSIT_LINES_DEPOSIT_ID_FK"))
    private Deposit deposit;

    // The cash/check tender deposited.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tender_id", nullable = false,
        foreignKey = @ForeignKey(name = "DEPOSIT_LINES_TENDER_ID_FK"))
    private CustomerPaymentTender tender;

    // The tender's amount (copied).
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // Server-assigned, 1-based, in entry order.
    @Column(name = "line_number", nullable = false)
    private int lineNumber;
}
