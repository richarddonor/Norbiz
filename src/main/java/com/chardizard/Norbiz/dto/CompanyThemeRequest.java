package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.ThemeMotion;
import com.chardizard.Norbiz.models.ThemeRadius;
import com.chardizard.Norbiz.models.ThemeSidebar;
import com.chardizard.Norbiz.models.ThemeSurface;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CompanyThemeRequest {

    /** Frontend palette key, CUSTOM, or null for the default. */
    @Size(max = 30)
    @Pattern(regexp = "^[A-Z_]+$", message = "must be an upper-case preset key")
    private String preset;

    /** Required when preset is CUSTOM. */
    @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "must be a #rrggbb hex color")
    private String color;

    @NotNull
    private ThemeRadius radius;

    @NotNull
    private ThemeSidebar sidebar;

    @NotNull
    private ThemeSurface surface;

    @NotNull
    private ThemeMotion motion;
}
