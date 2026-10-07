package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class BillOfMaterialLineRequest {

    @NotNull
    private Long itemId;

    // Quantity of this component needed per unit of the output item.
    @NotNull
    @DecimalMin(value = "0.0001")
    private BigDecimal quantity;
}
