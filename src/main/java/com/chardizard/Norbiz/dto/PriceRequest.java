package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.PriceType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PriceRequest {

    @NotNull
    private PriceType priceType;

    @NotNull
    @DecimalMin("0.00")
    private BigDecimal amount;
}