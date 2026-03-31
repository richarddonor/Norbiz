package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Getter
@Setter
public class UserResponse {
    private Long id;
    private String username;
    private String displayName;
    private String email;
    private Set<String> roles;
    private Set<Long> roleIds;
}
