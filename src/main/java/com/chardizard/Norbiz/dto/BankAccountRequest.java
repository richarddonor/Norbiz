package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class BankAccountRequest {

    @NotNull
    private Long companyId;

    @NotNull
    private Long bankId;

    @NotBlank
    @Size(max = 255)
    private String name;

    @NotBlank
    @Size(max = 50)
    private String accountNumber;

    // Balance per bank as of openingBalanceDate (may be negative for an overdrawn account); defaults to 0.
    @DecimalMin("-999999999999999.99")
    @DecimalMax("999999999999999.99")
    @Digits(integer = 17, fraction = 2)
    private BigDecimal openingBalance;

    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String openingBalanceDate;

    private boolean active = true;
}
