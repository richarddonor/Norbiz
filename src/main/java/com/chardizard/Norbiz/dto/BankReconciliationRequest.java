package com.chardizard.Norbiz.dto;

import jakarta.validation.Valid;
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
import java.util.List;

@Getter
@Setter
public class BankReconciliationRequest {

    @NotNull
    private Long bankAccountId;

    @NotBlank
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String statementDate;

    @NotNull
    @DecimalMin("-999999999999999.99")
    @DecimalMax("999999999999999.99")
    @Digits(integer = 17, fraction = 2)
    private BigDecimal statementEndingBalance;

    @Size(max = 100)
    private String sheetNumber;

    @Size(max = 255)
    private String remarks;

    // Book entries the statement shows as cleared (GET /bank-reconciliations/candidates).
    @Size(max = 5000)
    private List<@NotNull Long> clearedEntryIds;

    @Valid
    @Size(max = 100)
    private List<BankReconciliationAdjustmentRequest> adjustments;
}
