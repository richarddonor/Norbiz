package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(
    name = "item_prices",
    uniqueConstraints = @UniqueConstraint(name = "ITEM_PRICES_ITEM_PRICE_TYPE_UQ", columnNames = {"item_id", "price_type"})
)
public class ItemPrice extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false,
        foreignKey = @ForeignKey(name = "ITEM_PRICES_ITEM_ID_FK"))
    private Item item;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_type", nullable = false, length = 20)
    private PriceType priceType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
}
