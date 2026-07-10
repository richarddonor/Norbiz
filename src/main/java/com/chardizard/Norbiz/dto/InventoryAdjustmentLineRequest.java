package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class InventoryAdjustmentLineRequest {

    @NotNull
    private Long itemId;

    @NotNull
    private BigDecimal quantity;
}