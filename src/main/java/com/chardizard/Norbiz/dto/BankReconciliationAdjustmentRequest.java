package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.BankAdjustmentType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// A bank-only item on the statement: BANK_CHARGE (negative), INTEREST (positive), ADJUSTMENT (either sign).
@Getter
@Setter
public class BankReconciliationAdjustmentRequest {

    @NotNull
    private BankAdjustmentType type;

    @NotNull
    @DecimalMin("-999999999999999.99")
    @DecimalMax("999999999999999.99")
    @Digits(integer = 17, fraction = 2)
    private BigDecimal amount;

    @NotBlank
    @Size(max = 255)
    private String description;
}
