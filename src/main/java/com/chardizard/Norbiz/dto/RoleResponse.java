package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Getter
@Setter
public class RoleResponse {
    private Long id;
    private String name;
    private String displayName;
    private Set<String> permissions;
}
