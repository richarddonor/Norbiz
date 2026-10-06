package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** One SKU of an item, as edited on the item form. Matched to the item's existing SKUs by code. */
@Getter
@Setter
public class ItemSkuLineRequest {

    @NotBlank
    @Size(max = 100)
    private String skuCode;

    @NotNull
    @DecimalMin("0.00")
    private BigDecimal unitPrice;
}
