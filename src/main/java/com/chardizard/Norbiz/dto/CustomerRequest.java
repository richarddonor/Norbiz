package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.CustomerType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CustomerRequest {

    @NotNull
    private Long companyId;

    @Size(max = 100)
    private String code;

    @NotBlank
    @Size(max = 255)
    private String name;

    @NotNull
    private CustomerType type;

    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 50)
    private String phone;

    private boolean active = true;

    // Payment terms in days (due date = document date + termDays); defaults to 0 (due on the document date).
    @Min(0)
    @Max(365)
    private int termDays;
}
