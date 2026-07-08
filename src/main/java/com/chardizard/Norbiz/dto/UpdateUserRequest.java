package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Getter
@Setter
public class UpdateUserRequest {

    @NotBlank
    @Size(max = 255)
    private String username;

    @Size(max = 255)
    private String displayName;

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    @NotNull
    private Set<Long> roleIds;

    @NotNull
    private Set<Long> companyIds;
}