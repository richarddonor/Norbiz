package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BrandRequest {

    @NotNull
    private Long companyId;

    @NotBlank
    @Size(max = 255)
    private String name;

    /** Omitted = true on create, unchanged on update. Inactive records can't be newly picked in forms. */
    private Boolean active;
}