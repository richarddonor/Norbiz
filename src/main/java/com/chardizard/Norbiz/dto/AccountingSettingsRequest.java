package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AccountingSettingsRequest {

    @NotNull
    private Long companyId;

    // Go-live cut-off (yyyy-MM-dd): invoices dated on/after it are tracked as open items.
    @NotBlank
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String startDate;

    // Days a deposited check usually takes to clear; omit to keep the current value (default 3).
    @Min(0)
    @Max(60)
    private Integer checkClearingDays;
}
