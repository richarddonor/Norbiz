package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserPreferenceRequest {

    // Opaque JSON from the frontend. Capped well above any real layout to keep one user from
    // storing arbitrary blobs.
    @NotNull
    @Size(max = 10000)
    private String value;
}
