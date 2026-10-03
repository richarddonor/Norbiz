package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

// Designer save: replaces only the layout, so a stale designer tab can't revert the
// template's name/default/active flags (a full PUT re-sends whatever the tab loaded).
@Getter
@Setter
public class DocumentTemplateLayoutRequest {

    // Opaque JSON layout — exempt from the 255-char string rule (see DocumentTemplateRequest).
    @NotBlank
    private String layout;
}
