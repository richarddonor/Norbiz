package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(
    name = "item_groups",
    uniqueConstraints = @UniqueConstraint(name = "ITEM_GROUPS_COMPANY_NAME_UQ", columnNames = {"company_id", "name"})
)
public class ItemGroup extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "ITEM_GROUPS_COMPANY_ID_FK"))
    private Company company;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "bn_initials", length = 20)
    private String bnInitials;

    // Percentages, e.g. 5.25 = 5.25%
    @Column(name = "commission_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal commissionRate = BigDecimal.ZERO;

    @Column(name = "focal_commission_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal focalCommissionRate = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean active = true;
}
