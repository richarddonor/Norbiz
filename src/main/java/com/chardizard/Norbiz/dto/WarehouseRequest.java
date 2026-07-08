package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WarehouseRequest {

    @NotNull
    private Long companyId;

    @Size(max = 100)
    private String code;

    @NotBlank
    @Size(max = 255)
    private String name;

    private boolean active = true;
}