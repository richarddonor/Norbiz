package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;

// Not Auditable: immutable once posted (see PullOutReceive).
@Getter
@Setter
@Entity
@Table(name = "pull_out_receive_lines")
public class PullOutReceiveLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pull_out_receive_id", nullable = false,
        foreignKey = @ForeignKey(name = "PULL_OUT_RECEIVE_LINES_PULL_OUT_RECEIVE_ID_FK"))
    private PullOutReceive pullOutReceive;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "PULL_OUT_RECEIVE_LINES_ITEM_ID_FK"))
    private Item item;

    // The Outlet Pull Out line this amount was received against (a request line may split across several).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_pull_out_line_id", nullable = false,
        foreignKey = @ForeignKey(name = "PULL_OUT_RECEIVE_LINES_OUTLET_PULL_OUT_LINE_ID_FK"))
    private OutletPullOutLine outletPullOutLine;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    // Inert: nothing loads from a Pull Out Receive.
    @Column(name = "quantity_loaded", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityLoaded = BigDecimal.ZERO;
}
