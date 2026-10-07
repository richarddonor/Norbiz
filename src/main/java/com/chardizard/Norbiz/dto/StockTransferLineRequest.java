package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class StockTransferLineRequest {

    @NotNull
    private Long itemId;

    @NotNull
    @DecimalMin(value = "0.0001")
    private BigDecimal quantity;

    // Optional — preloaded from the item's UNIT_PRICE when omitted.
    @DecimalMin(value = "0")
    private BigDecimal unitPrice;
}
