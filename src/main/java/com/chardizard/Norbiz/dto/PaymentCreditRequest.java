package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// Uses part of an existing CREDIT open item (an advance, supplier debit memo or customer credit memo) on a payment,
// applying it to one of the party's CHARGE open items.
@Getter
@Setter
public class PaymentCreditRequest {

    @NotNull
    private Long creditItemId;

    @NotNull
    private Long chargeItemId;

    @NotNull
    @DecimalMin(value = "0.01")
    @Digits(integer = 17, fraction = 2)
    private BigDecimal amount;
}
