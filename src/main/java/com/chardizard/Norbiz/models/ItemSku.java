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
    // item lists each code at most once. Item-less SKUs (item_id null) are not constrained.
    uniqueConstraints = @UniqueConstraint(name = "ITEM_SKUS_ITEM_SKU_CODE_UQ", columnNames = {"item_id", "sku_code"}),
    // SKUs are looked up by brand, then category, then price (GET /item-skus?brandId=&itemCategoryId=&price=).
    indexes = @Index(name = "ITEM_SKUS_BRAND_CATEGORY_PRICE_IX", columnList = "company_id, brand_id, item_category_id, unit_price")
)
public class ItemSku extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The owning company. Equals the item's company when there is an item; it is what scopes a SKU that
    // has none (e.g. a legacy price point that matched no item, or an inactive one).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false,
        foreignKey = @ForeignKey(name = "ITEM_SKUS_COMPANY_ID_FK"))
    private Company company;

    // Optional: a SKU may exist on its own, not yet assigned to an item.
    @AuditParent
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id",
        foreignKey = @ForeignKey(name = "ITEM_SKUS_ITEM_ID_FK"))
    private Item item;

    @Column(name = "sku_code", nullable = false, length = 100)
    private String skuCode;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_category_id",
        foreignKey = @ForeignKey(name = "ITEM_SKUS_ITEM_CATEGORY_ID_FK"))
    private ItemCategory itemCategory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id",
        foreignKey = @ForeignKey(name = "ITEM_SKUS_BRAND_ID_FK"))
    private Brand brand;

    // Which item price the SKU price stands for: UNIT_PRICE (regular) or FOCAL_PRICE. Null when unknown.
    @Enumerated(EnumType.STRING)
    @Column(name = "price_type", length = 20)
    private PriceType priceType;

    // The store's own item code for this SKU (legacy price point ItemCode).
    @Column(name = "store_item_code", length = 100)
    private String storeItemCode;

    @Column(length = 100)
    private String barcode;

    @Column(name = "vendor_part", length = 100)
    private String vendorPart;

    @Column(name = "rds_description")
    private String rdsDescription;

    @Column(nullable = false)
    private boolean active = true;

    // Store flags carried over from legacy price points (IsRDSSKU / IsLandmarkSKU).
    @Column(name = "rds_sku", nullable = false)
    private boolean rdsSku = false;

    @Column(name = "landmark_sku", nullable = false)
    private boolean landmarkSku = false;
}
