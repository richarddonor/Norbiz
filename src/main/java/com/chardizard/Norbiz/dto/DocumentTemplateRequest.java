package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DocumentTemplateRequest {

    @NotNull
    private Long companyId;

    @NotBlank
    @Size(max = 50)
    private String documentType;

    @NotBlank
    @Size(max = 255)
    private String name;

    // Opaque JSON layout — no size cap here beyond what the DB column allows (TEXT);
    // this deliberately does not follow the general "strings under 255 chars" rule.
    @NotBlank
    private String layout;

    private boolean defaultTemplate = false;

    private boolean active = true;
}
