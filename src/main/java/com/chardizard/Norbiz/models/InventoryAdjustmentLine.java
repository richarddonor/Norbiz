package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// Not Auditable: adjustment lines are immutable once posted (see InventoryAdjustment),
// so there is nothing to diff/track — the ledger (InventoryMovement) is the audit trail.
@Getter
@Setter
@Entity
@Table(name = "inventory_adjustment_lines")
public class InventoryAdjustmentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "adjustment_id", nullable = false,
        foreignKey = @ForeignKey(name = "INVENTORY_ADJUSTMENT_LINES_ADJUSTMENT_ID_FK"))
    private InventoryAdjustment adjustment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "INVENTORY_ADJUSTMENT_LINES_ITEM_ID_FK"))
    private Item item;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;
}