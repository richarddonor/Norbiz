package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Set;

@Getter
@Setter
public class MeResponse {
    private String username;
    private String displayName;
    private List<String> roles;
    private Set<String> permissions;
    private List<CompanyInfo> companies;

    @Getter
    @Setter
    public static class CompanyInfo {
        private Long id;
        private String name;
    }
}
