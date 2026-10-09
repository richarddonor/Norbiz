package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.PriceType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class ItemSkuResponse {
    private Long id;
    private Long companyId;
    // Null for a SKU not assigned to an item.
    private Long itemId;
    private String itemCode;
    private String itemName;
    private String skuCode;
    private BigDecimal unitPrice;
    private Long itemCategoryId;
    private String itemCategoryName;
    private Long brandId;
    private String brandName;
    private PriceType priceType;
    private String storeItemCode;
    private String barcode;
    private String vendorPart;
    private String rdsDescription;
    private boolean active;
    private boolean rdsSku;
    private boolean landmarkSku;
}
