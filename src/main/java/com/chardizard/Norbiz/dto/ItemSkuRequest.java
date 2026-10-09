package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.PriceType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class ItemSkuRequest {

    // Create only (ignored on update): the item the SKU belongs to. Optional — a SKU may exist without
    // an item, in which case companyId is required.
    private Long itemId;

    // Create only (ignored on update): the owning company of an item-less SKU. When itemId is given the
    // item's company is used, and a different companyId is rejected.
    private Long companyId;

    @NotBlank
    @Size(max = 100)
    private String skuCode;

    @NotNull
    @DecimalMin("0.00")
    private BigDecimal unitPrice;

    // The fields below are optional. On update, null leaves the current value unchanged; a blank
    // string clears a text field.

    private Long itemCategoryId;

    private Long brandId;

    // UNIT_PRICE (regular) or FOCAL_PRICE.
    private PriceType priceType;

    @Size(max = 100)
    private String storeItemCode;

    @Size(max = 100)
    private String barcode;

    @Size(max = 100)
    private String vendorPart;

    @Size(max = 255)
    private String rdsDescription;

    private Boolean active;

    private Boolean rdsSku;

    private Boolean landmarkSku;
}
