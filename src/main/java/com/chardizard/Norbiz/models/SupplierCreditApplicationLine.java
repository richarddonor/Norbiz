package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

// Not Auditable: immutable once posted (see SupplierCreditApplication).
@Getter
@Setter
@Entity
@Table(
    name = "supplier_credit_application_lines"
)
public class SupplierCreditApplicationLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_credit_application_id", nullable = false,
        foreignKey = @ForeignKey(name = "SUPPLIER_CREDIT_APPLICATION_LINES_SUPPLIER_CREDIT_APPLICATION_ID_FK"))
    private SupplierCreditApplication supplierCreditApplication;

    // The CREDIT open item applied.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credit_item_id", nullable = false,
        foreignKey = @ForeignKey(name = "SUPPLIER_CREDIT_APPLICATION_LINES_CREDIT_ITEM_ID_FK"))
    private OpenItem creditItem;

    // The CHARGE open item it pays.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "charge_item_id", nullable = false,
        foreignKey = @ForeignKey(name = "SUPPLIER_CREDIT_APPLICATION_LINES_CHARGE_ITEM_ID_FK"))
    private OpenItem chargeItem;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // Server-assigned, 1-based, in entry order.
    @Column(name = "line_number", nullable = false)
    private int lineNumber;
}
