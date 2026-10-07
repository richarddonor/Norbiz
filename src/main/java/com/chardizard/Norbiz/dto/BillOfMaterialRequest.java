package com.chardizard.Norbiz.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class BillOfMaterialRequest {

    @NotNull
    private Long companyId;

    @NotBlank
    @Size(max = 100)
    private String code;

    // The finished item one unit of this BOM produces.
    @NotNull
    private Long itemId;

    /** Defaults to true when omitted. */
    private Boolean active;

    @NotEmpty
    @Valid
    private List<BillOfMaterialLineRequest> components;
}
