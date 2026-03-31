package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Getter
@Setter
public class RoleRequest {
    private String name;
    private String displayName;
    private Set<Long> permissionIds;
}
