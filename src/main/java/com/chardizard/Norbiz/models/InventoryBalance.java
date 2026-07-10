package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

// Not Auditable: this is a system-maintained running cache, not a user-edited
// record — InventoryMovement is the immutable history behind every change here.
@Getter
@Setter
@Entity
@Table(
    name = "inventory_balances",
    uniqueConstraints = @UniqueConstraint(name = "INVENTORY_BALANCES_ITEM_WAREHOUSE_UQ", columnNames = {"item_id", "warehouse_id"})
)
public class InventoryBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "INVENTORY_BALANCES_ITEM_ID_FK"))
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false,
        foreignKey = @ForeignKey(name = "INVENTORY_BALANCES_WAREHOUSE_ID_FK"))
    private Warehouse warehouse;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "transit_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal transitQuantity = BigDecimal.ZERO;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}