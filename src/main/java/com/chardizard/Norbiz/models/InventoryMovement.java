package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

// Not Auditable: the ledger is append-only by design — it IS the audit trail for
// stock changes, so there is nothing to diff/track about its own rows.
@Getter
@Setter
@Entity
@Table(name = "inventory_movements")
public class InventoryMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "INVENTORY_MOVEMENTS_COMPANY_ID_FK"))
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "INVENTORY_MOVEMENTS_ITEM_ID_FK"))
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false,
        foreignKey = @ForeignKey(name = "INVENTORY_MOVEMENTS_WAREHOUSE_ID_FK"))
    private Warehouse warehouse;

    @Column(name = "quantity_delta", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityDelta;

    @Column(name = "transit_quantity_delta", nullable = false, precision = 19, scale = 4)
    private BigDecimal transitQuantityDelta = BigDecimal.ZERO;

    // Business-effective date, represented as the UTC-midnight instant of that day
    // (see DateRangeUtils) so this column reuses the same date-range filtering
    // infrastructure as every other Instant-typed audited column.
    @Column(name = "movement_date", nullable = false)
    private Instant movementDate;

    @Column(name = "source_type", nullable = false, length = 50)
    private String sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "reference_number", nullable = false, length = 50)
    private String referenceNumber;

    @Column(name = "sheet_number", length = 100)
    private String sheetNumber;

    @Column(length = 255)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;
}