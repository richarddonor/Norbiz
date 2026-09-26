package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePasswordRequest {

    @NotBlank
    @Size(max = 255)
    private String currentPassword;

    @NotBlank
    @Size(min = 8, max = 255)
    private String newPassword;
}
