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

    // Optional: the bill of materials this output was built from (must produce this item).
    private Long billOfMaterialId;
}
