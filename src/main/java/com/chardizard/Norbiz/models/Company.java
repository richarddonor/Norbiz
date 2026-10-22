package com.chardizard.Norbiz.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "companies")
public class Company extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    // ---- UI theme (docs/THEMING.md) — edited only through CompanyThemeService ----

    /** A frontend palette key (e.g. EMERALD), CUSTOM to use themeColor, or null for the default Norbiz blue. */
    @Column(length = 30)
    private String themePreset;

    /** #rrggbb, used when themePreset is CUSTOM. */
    @Column(length = 7)
    private String themeColor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ThemeRadius themeRadius = ThemeRadius.SOFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ThemeSidebar themeSidebar = ThemeSidebar.LIGHT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ThemeSurface themeSurface = ThemeSurface.PLAIN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ThemeMotion themeMotion = ThemeMotion.DOCUMENT;

    /** Relative to the upload dir: {companyId}/branding/logo.{ext}. */
    @Column(length = 255)
    private String logoPath;

    /** Bumped on every theme/logo change; the logo URL carries it so browsers drop the cached image. */
    private Instant themeUpdatedAt;
}
