package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.controllers.CompanyThemeController;
import com.chardizard.Norbiz.dto.CompanyThemeRequest;
import com.chardizard.Norbiz.dto.CompanyThemeResponse;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Runs against the configured Postgres (same as NorbizApplicationTests); every test rolls back.
// Logos go to a throwaway directory under java.io.tmpdir.
@SpringBootTest(properties = "app.item-image.upload-dir=${java.io.tmpdir}/norbiz-company-theme-test")
@Transactional
class CompanyThemeServiceTest {

    // Smallest valid-looking headers; only the magic bytes are checked.
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};

    @Autowired CompanyThemeService service;
    @Autowired CompanyThemeController controller;
    @Autowired CompanyRepository companyRepository;
    @Autowired UserRepository userRepository;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private Company company;
    private Company otherCompany;
    private String member;

    @BeforeEach
    void setUp() {
        company = newCompany("Theme Co " + suffix);
        otherCompany = newCompany("Other Theme Co " + suffix);
        User u = new User();
        u.setUsername("theme_user_" + suffix);
        u.setEmail(u.getUsername() + "@test.local");
        u.setPassword("x");
        u.setCompanies(Set.of(company));
        member = userRepository.save(u).getUsername();
    }

    @Test
    void newCompanyHasDefaultTheme() {
        CompanyThemeResponse theme = service.get(company.getId(), member);
        assertThat(theme.preset()).isNull();
        assertThat(theme.radius()).isEqualTo(ThemeRadius.SOFT);
        assertThat(theme.sidebar()).isEqualTo(ThemeSidebar.LIGHT);
        assertThat(theme.surface()).isEqualTo(ThemeSurface.PLAIN);
        assertThat(theme.motion()).isEqualTo(ThemeMotion.DOCUMENT);
        assertThat(theme.logoUrl()).isNull();
    }

    @Test
    void updateStoresChoicesAndDropsColorForNamedPreset() {
        CompanyThemeResponse custom = service.update(company.getId(), request("CUSTOM", "#AB12CD"), member);
        assertThat(custom.preset()).isEqualTo("CUSTOM");
        assertThat(custom.color()).isEqualTo("#ab12cd");
        assertThat(custom.sidebar()).isEqualTo(ThemeSidebar.DARK);

        CompanyThemeResponse named = service.update(company.getId(), request("EMERALD", "#AB12CD"), member);
        assertThat(named.preset()).isEqualTo("EMERALD");
        assertThat(named.color()).isNull();
    }

    @Test
    void customPresetNeedsColor() {
        assertThatThrownBy(() -> service.update(company.getId(), request("CUSTOM", null), member))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void otherCompanysThemeIsOffLimits() {
        assertThatThrownBy(() -> service.get(otherCompany.getId(), member)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.update(otherCompany.getId(), request("EMERALD", null), member))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.uploadLogo(otherCompany.getId(), PNG, member))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @WithMockUser(authorities = "VIEW_ITEM")
    void updateNeedsManageCompanyThemePermission() {
        assertThatThrownBy(() -> controller.update(null, company.getId(), request("EMERALD", null)))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void logoUploadServeAndRemove() throws Exception {
        CompanyThemeResponse uploaded = service.uploadLogo(company.getId(), PNG, member);
        assertThat(uploaded.logoUrl()).startsWith("/company-logos/" + company.getId() + "/logo.png?v=");
        assertThat(service.resolveLogoFile(company.getId(), "logo.png")).isPresent();

        CompanyThemeResponse removed = service.deleteLogo(company.getId(), member);
        assertThat(removed.logoUrl()).isNull();
        assertThat(service.resolveLogoFile(company.getId(), "logo.png")).isEmpty();
    }

    @Test
    void logoMustBeASmallRasterImage() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.uploadLogo(company.getId(), svg, member))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.uploadLogo(company.getId(), new byte[0], member))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] huge = new byte[CompanyThemeService.MAX_LOGO_BYTES + 1];
        System.arraycopy(PNG, 0, huge, 0, PNG.length);
        assertThatThrownBy(() -> service.uploadLogo(company.getId(), huge, member))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void logoServeRejectsTraversalAndOtherNames() {
        assertThat(service.resolveLogoFile(company.getId(), "../../etc/passwd")).isEmpty();
        assertThat(service.resolveLogoFile(company.getId(), "..")).isEmpty();
        assertThat(service.resolveLogoFile(company.getId(), "other.png")).isEmpty();
    }

    private Company newCompany(String name) {
        Company c = new Company();
        c.setName(name);
        return companyRepository.save(c);
    }

    private static CompanyThemeRequest request(String preset, String color) {
        CompanyThemeRequest r = new CompanyThemeRequest();
        r.setPreset(preset);
        r.setColor(color);
        r.setRadius(ThemeRadius.SHARP);
        r.setSidebar(ThemeSidebar.DARK);
        r.setSurface(ThemeSurface.PAPER);
        r.setMotion(ThemeMotion.SLIDE);
        return r;
    }
}
