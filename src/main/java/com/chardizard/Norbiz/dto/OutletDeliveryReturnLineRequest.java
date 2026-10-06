package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class OutletDeliveryReturnLineRequest {

    @NotNull
    private Long itemId;

    // Amount being returned now — must not exceed the Outlet Delivery Receipt's outstanding for this item.
    @NotNull
    @DecimalMin(value = "0.0001")
    private BigDecimal quantity;
}
