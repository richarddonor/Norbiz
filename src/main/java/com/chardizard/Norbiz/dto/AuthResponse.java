package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Getter
@RequiredArgsConstructor
public class AuthResponse {
    private final String token;
    private final List<CompanyInfo> companies;
    private final boolean requiresCompanySelection;

    @Getter
    @RequiredArgsConstructor
    public static class CompanyInfo {
        private final Long id;
        private final String name;
    }
}