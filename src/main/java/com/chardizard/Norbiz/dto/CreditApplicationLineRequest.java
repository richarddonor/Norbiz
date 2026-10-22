package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// Applies part of an existing CREDIT open item (an advance or credit memo) to a CHARGE open item.
@Getter
@Setter
public class CreditApplicationLineRequest {

    @NotNull
    private Long creditItemId;

    @NotNull
    private Long chargeItemId;

    @NotNull
    @DecimalMin(value = "0.01")
    @Digits(integer = 17, fraction = 2)
    private BigDecimal amount;
}
