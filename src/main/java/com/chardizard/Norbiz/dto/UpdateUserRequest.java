package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Getter
@Setter
public class UpdateUserRequest {
    private String username;
    private String displayName;
    private String email;
    private Set<Long> roleIds;
}