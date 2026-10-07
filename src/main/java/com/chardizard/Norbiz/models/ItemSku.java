package com.chardizard.Norbiz.models;

import com.chardizard.Norbiz.audit.AuditParent;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(
    name = "item_skus",
    // A SKU code may be shared by several items (legacy department-store SKUs cover many items), but an
    // item lists each code at most once.
    uniqueConstraints = @UniqueConstraint(name = "ITEM_SKUS_ITEM_SKU_CODE_UQ", columnNames = {"item_id", "sku_code"})
)
public class ItemSku extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @AuditParent
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "ITEM_SKUS_ITEM_ID_FK"))
    private Item item;

    @Column(name = "sku_code", nullable = false, length = 100)
    private String skuCode;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;
}
