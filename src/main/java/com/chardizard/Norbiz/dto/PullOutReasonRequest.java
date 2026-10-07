package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PullOutReasonRequest {

    @NotNull
    private Long companyId;

    @NotBlank
    @Size(max = 255)
    private String name;

    /** Defaults to true when omitted. */
    private Boolean active;
}
