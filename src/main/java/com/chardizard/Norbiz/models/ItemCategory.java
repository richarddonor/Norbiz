package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "item_categories",
    uniqueConstraints = @UniqueConstraint(name = "ITEM_CATEGORIES_COMPANY_NAME_UQ", columnNames = {"company_id", "name"})
)
public class ItemCategory extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "ITEM_CATEGORIES_COMPANY_ID_FK"))
    private Company company;

    @Column(nullable = false, length = 255)
    private String name;
}