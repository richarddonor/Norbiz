package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class AssemblyMaterialRequest {

    @NotNull
    private Long itemId;

    // Total consumed for the whole assembly (the form prefills BOM component quantity x output quantity).
    @NotNull
    @DecimalMin(value = "0.0001")
    private BigDecimal quantity;
}
