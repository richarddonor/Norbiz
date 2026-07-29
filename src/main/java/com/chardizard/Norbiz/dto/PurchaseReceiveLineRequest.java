package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PurchaseReceiveLineRequest {

    @NotNull
    private Long itemId;

    // Quantity to receive now — must not exceed the source line's outstanding
    // (quantity - quantityLoaded) amount. See PurchaseReceiveService.create.
    @NotNull
    @DecimalMin(value = "0.0001")
    private BigDecimal quantity;
}
