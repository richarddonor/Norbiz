package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.ThemeMotion;
import com.chardizard.Norbiz.models.ThemeRadius;
import com.chardizard.Norbiz.models.ThemeSidebar;
import com.chardizard.Norbiz.models.ThemeSurface;

/** A company's UI theme (docs/THEMING.md). logoUrl is relative to the API base, or null when no logo is set. */
public record CompanyThemeResponse(
        String preset,
        String color,
        ThemeRadius radius,
        ThemeSidebar sidebar,
        ThemeSurface surface,
        ThemeMotion motion,
        String logoUrl) {

    public static CompanyThemeResponse of(Company c) {
        String logoUrl = null;
        if (c.getLogoPath() != null) {
            String version = c.getThemeUpdatedAt() != null ? "?v=" + c.getThemeUpdatedAt().toEpochMilli() : "";
            // logoPath {companyId}/branding/logo.png is served at /company-logos/{companyId}/logo.png
            String filename = c.getLogoPath().substring(c.getLogoPath().lastIndexOf('/') + 1);
            logoUrl = "/company-logos/" + c.getId() + "/" + filename + version;
        }
        return new CompanyThemeResponse(c.getThemePreset(), c.getThemeColor(), c.getThemeRadius(),
                c.getThemeSidebar(), c.getThemeSurface(), c.getThemeMotion(), logoUrl);
    }
}
