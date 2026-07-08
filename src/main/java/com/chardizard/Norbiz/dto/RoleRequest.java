package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Getter
@Setter
public class RoleRequest {

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 255)
    private String displayName;

    private Set<Long> permissionIds;
}