package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PurchaseOrderLineRequest {

    @NotNull
    private Long itemId;

    @NotNull
    @DecimalMin(value = "0.0001")
    private BigDecimal quantity;

    // Optional — preloaded from the item's current cost price when omitted (see PurchaseOrderService).
    // Ignored (forced to 0) for callers without VIEW_COST_PRICE.
    private BigDecimal costPrice;
}
