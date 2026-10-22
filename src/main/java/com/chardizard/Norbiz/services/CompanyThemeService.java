package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.CompanyThemeRequest;
import com.chardizard.Norbiz.dto.CompanyThemeResponse;
import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.security.CompanyAccess;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Optional;

/**
 * Per-company UI theme and logo (docs/THEMING.md). The backend only stores the choices; colors are derived and
 * applied by the frontend.
 */
@Service
@RequiredArgsConstructor
public class CompanyThemeService {

    private static final Logger log = LoggerFactory.getLogger(CompanyThemeService.class);

    public static final String CUSTOM_PRESET = "CUSTOM";
    static final int MAX_LOGO_BYTES = 512 * 1024;

    private final CompanyRepository companyRepository;
    private final CompanyAccess companyAccess;

    @Value("${app.item-image.upload-dir}")
    private String uploadDir;

    @Transactional(readOnly = true)
    public CompanyThemeResponse get(Long companyId, String username) {
        return CompanyThemeResponse.of(findCompany(companyId, username));
    }

    @Transactional
    public CompanyThemeResponse update(Long companyId, CompanyThemeRequest request, String username) {
        Company company = findCompany(companyId, username);
        if (CUSTOM_PRESET.equals(request.getPreset()) && request.getColor() == null) {
            throw new IllegalArgumentException("A custom theme needs a color");
        }
        company.setThemePreset(request.getPreset());
        // The hex only means something for CUSTOM; drop it otherwise so a stale value can't resurface.
        company.setThemeColor(CUSTOM_PRESET.equals(request.getPreset()) ? request.getColor().toLowerCase() : null);
        company.setThemeRadius(request.getRadius());
        company.setThemeSidebar(request.getSidebar());
        company.setThemeSurface(request.getSurface());
        company.setThemeMotion(request.getMotion());
        company.setThemeUpdatedAt(Instant.now());
        Company saved = companyRepository.save(company);
        log.info("User '{}' updated theme of company {}: preset={}, color={}, radius={}, sidebar={}, surface={}, motion={}",
                username, companyId, saved.getThemePreset(), saved.getThemeColor(), saved.getThemeRadius(),
                saved.getThemeSidebar(), saved.getThemeSurface(), saved.getThemeMotion());
        return CompanyThemeResponse.of(saved);
    }

    @Transactional
    public CompanyThemeResponse uploadLogo(Long companyId, byte[] bytes, String username) throws IOException {
        Company company = findCompany(companyId, username);
        if (bytes.length == 0) throw new IllegalArgumentException("The logo file is empty");
        if (bytes.length > MAX_LOGO_BYTES) throw new IllegalArgumentException("The logo must be 512 KB or smaller");
        // Trust the bytes, not the filename or Content-Type: only raster formats, so the public URL can't serve script.
        String ext = detectImageExtension(bytes)
                .orElseThrow(() -> new IllegalArgumentException("The logo must be a PNG, JPEG or WEBP image"));

        Path root = resolveUploadDir();
        Path dir = root.resolve(company.getId().toString()).resolve("branding");
        Files.createDirectories(dir);
        Path target = dir.resolve("logo" + ext);
        if (!target.toAbsolutePath().normalize().startsWith(root)) throw new SecurityException("Invalid file path");

        deleteLogoFile(company);
        Files.write(target, bytes);

        company.setLogoPath(company.getId() + "/branding/logo" + ext);
        company.setThemeUpdatedAt(Instant.now());
        Company saved = companyRepository.save(company);
        log.info("User '{}' uploaded logo '{}' ({} bytes) for company {}", username, saved.getLogoPath(), bytes.length, companyId);
        return CompanyThemeResponse.of(saved);
    }

    @Transactional
    public CompanyThemeResponse deleteLogo(Long companyId, String username) throws IOException {
        Company company = findCompany(companyId, username);
        deleteLogoFile(company);
        company.setLogoPath(null);
        company.setThemeUpdatedAt(Instant.now());
        Company saved = companyRepository.save(company);
        log.info("User '{}' removed the logo of company {}", username, companyId);
        return CompanyThemeResponse.of(saved);
    }

    /** The logo file for the public serve endpoint, or empty if the company has none or the name doesn't match it. */
    public Optional<Path> resolveLogoFile(Long companyId, String filename) {
        Path root = resolveUploadDir();
        Path file = root.resolve(companyId.toString()).resolve("branding").resolve(filename).normalize();
        if (!file.startsWith(root) || !filename.matches("logo\\.(png|jpg|webp)")) return Optional.empty();
        return Files.isReadable(file) ? Optional.of(file) : Optional.empty();
    }

    private Company findCompany(Long companyId, String username) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + companyId));
        companyAccess.assertCompanyAccess(username, company.getId());
        return company;
    }

    private void deleteLogoFile(Company company) throws IOException {
        if (company.getLogoPath() == null) return;
        Path root = resolveUploadDir();
        Path old = root.resolve(company.getLogoPath()).normalize();
        if (old.startsWith(root)) Files.deleteIfExists(old);
    }

    private Path resolveUploadDir() {
        return Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    static Optional<String> detectImageExtension(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return Optional.of(".png");
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return Optional.of(".jpg");
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return Optional.of(".webp");
        return Optional.empty();
    }
}
