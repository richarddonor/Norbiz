package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class AssemblyOutputRequest {

    @NotNull
    private Long itemId;

    @NotNull
    @DecimalMin(value = "0.0001")
    private BigDecimal quantity;

    // The bill of materials this output is built from (must produce this item); its components x quantity
    // are the raw materials consumed.
    @NotNull
    private Long billOfMaterialId;
}
