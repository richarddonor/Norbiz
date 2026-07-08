package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@Entity
@Table(
    name = "items",
    uniqueConstraints = @UniqueConstraint(name = "ITEMS_COMPANY_ITEM_CODE_UQ", columnNames = {"company_id", "item_code"})
)
public class Item extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "ITEMS_COMPANY_ID_FK"))
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_category_id", nullable = false,
        foreignKey = @ForeignKey(name = "ITEMS_ITEM_CATEGORY_ID_FK"))
    private ItemCategory itemCategory;

    @Column(name = "item_code", nullable = false, length = 100)
    private String itemCode;

    @Column(nullable = false)
    private String name;

    // Relative path from the application's configured image root.
    // Example: "items/2024/acme-widget-front.jpg"
    @Column(name = "image_path", length = 500)
    private String imagePath;

    @Column(nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ItemSku> skus = new ArrayList<>();

    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ItemPrice> prices = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "item_tags",
        joinColumns = @JoinColumn(name = "item_id",
            foreignKey = @ForeignKey(name = "ITEM_TAGS_ITEM_ID_FK"))
    )
    @Column(name = "tag", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private Set<ItemTag> tags = new HashSet<>();
}