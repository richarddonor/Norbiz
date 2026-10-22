package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;
import org.hibernate.annotations.Synchronize;

// Read-only line of the "Customer Payment - Detailed" report: the payment's tenders and the credits it used, in one
// numbering. Not a table: a query over both (ids are doubled so a tender's and a credit's never collide).
@Getter
@Entity
@Immutable
@Subselect("""
    select t.id * 2 as id, t.customer_payment_id, t.line_number, t.amount, t.mode, t.check_number, t.status,
           cast(null as varchar) as credit_reference, cast(null as varchar) as credit_source_type,
           cast(null as varchar) as charge_reference
    from customer_payment_tenders t
    union all
    select c.id * 2 + 1, c.customer_payment_id, c.line_number, c.amount, 'CREDIT', null, null,
           ci.reference_number, ci.source_type, ch.reference_number
    from customer_payment_credits c
    join open_items ci on ci.id = c.credit_item_id
    join open_items ch on ch.id = c.charge_item_id
    """)
@Synchronize({"customer_payment_tenders", "customer_payment_credits", "open_items"})
public class CustomerPaymentReportLine {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_payment_id")
    private CustomerPayment customerPayment;

    @Column(name = "line_number")
    private Integer lineNumber;

    @Column(name = "amount")
    private BigDecimal amount;

    // The tender's mode, or CREDIT for a credit used.
    @Column(name = "mode")
    private String mode;

    @Column(name = "check_number")
    private String checkNumber;

    @Column(name = "status")
    private String status;

    @Column(name = "credit_reference")
    private String creditReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "credit_source_type")
    private OpenItemSourceType creditSourceType;

    @Column(name = "charge_reference")
    private String chargeReference;
}
