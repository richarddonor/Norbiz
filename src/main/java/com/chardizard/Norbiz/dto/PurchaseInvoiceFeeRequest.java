package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PurchaseInvoiceFeeRequest {

    @NotNull
    @Size(max = 255)
    private String description;

    @NotNull
    @DecimalMin(value = "0.01")
    private BigDecimal amount;
}
