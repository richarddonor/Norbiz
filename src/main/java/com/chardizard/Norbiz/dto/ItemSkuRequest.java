package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class ItemSkuRequest {

    // Required only for creation; ignored on update (SKU stays tied to its item)
    private Long itemId;

    @NotBlank
    private String skuCode;

    @NotNull
    private BigDecimal unitPrice;
}
