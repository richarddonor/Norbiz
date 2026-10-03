package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class ItemGroupRequest {

    @NotNull
    private Long companyId;

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 255)
    private String description;

    @Size(max = 20)
    private String bnInitials;

    /** Percentage, 0–100 with up to 2 decimal places. */
    @NotNull
    @DecimalMin("0.00")
    @DecimalMax("100.00")
    @Digits(integer = 3, fraction = 2)
    private BigDecimal commissionRate;

    /** Percentage, 0–100 with up to 2 decimal places. */
    @NotNull
    @DecimalMin("0.00")
    @DecimalMax("100.00")
    @Digits(integer = 3, fraction = 2)
    private BigDecimal focalCommissionRate;

    /** Defaults to true when omitted. */
    private Boolean active;
}
