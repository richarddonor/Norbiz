package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.CompanyThemeRequest;
import com.chardizard.Norbiz.dto.CompanyThemeResponse;
import com.chardizard.Norbiz.services.CompanyThemeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

@Tag(name = "Company Theme", description = "Per-company UI theme and logo (docs/THEMING.md)")
@RestController
@RequiredArgsConstructor
public class CompanyThemeController {

    private final CompanyThemeService companyThemeService;

    @Operation(summary = "Get company theme", description = "Any user of the company may read its theme.")
    @ApiResponse(responseCode = "200", description = "Theme returned")
    @ApiResponse(responseCode = "403", description = "No access to the company")
    @GetMapping("/companies/{id}/theme")
    public ResponseEntity<AppResponse<CompanyThemeResponse>> get(@AuthenticationPrincipal UserDetails userDetails,
                                                                 @Parameter(description = "Company ID") @PathVariable Long id) {
        return ResponseEntity.ok(AppResponse.of(companyThemeService.get(id, userDetails.getUsername())));
    }

    @Operation(summary = "Update company theme", description = "Sets the color preset (or CUSTOM + hex), corner radius, sidebar style, form surface and motion style.")
    @ApiResponse(responseCode = "200", description = "Theme updated")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE_COMPANY_THEME permission or no access to the company")
    @PutMapping("/companies/{id}/theme")
    @PreAuthorize("hasAuthority('MANAGE_COMPANY_THEME')")
    public ResponseEntity<AppResponse<CompanyThemeResponse>> update(@AuthenticationPrincipal UserDetails userDetails,
                                                                    @Parameter(description = "Company ID") @PathVariable Long id,
                                                                    @Valid @RequestBody CompanyThemeRequest request) {
        return ResponseEntity.ok(AppResponse.of(companyThemeService.update(id, request, userDetails.getUsername())));
    }

    @Operation(summary = "Upload company logo", description = "PNG, JPEG or WEBP, max 512 KB. Replaces any existing logo.")
    @ApiResponse(responseCode = "200", description = "Logo uploaded; returns the updated theme")
    @ApiResponse(responseCode = "400", description = "Empty, too large or not a supported image")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE_COMPANY_THEME permission or no access to the company")
    @PostMapping("/companies/{id}/logo")
    @PreAuthorize("hasAuthority('MANAGE_COMPANY_THEME')")
    public ResponseEntity<AppResponse<CompanyThemeResponse>> uploadLogo(@AuthenticationPrincipal UserDetails userDetails,
                                                                        @Parameter(description = "Company ID") @PathVariable Long id,
                                                                        @Parameter(description = "Logo image") @RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(AppResponse.of(companyThemeService.uploadLogo(id, file.getBytes(), userDetails.getUsername())));
    }

    @Operation(summary = "Remove company logo")
    @ApiResponse(responseCode = "200", description = "Logo removed; returns the updated theme")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE_COMPANY_THEME permission or no access to the company")
    @DeleteMapping("/companies/{id}/logo")
    @PreAuthorize("hasAuthority('MANAGE_COMPANY_THEME')")
    public ResponseEntity<AppResponse<CompanyThemeResponse>> deleteLogo(@AuthenticationPrincipal UserDetails userDetails,
                                                                        @Parameter(description = "Company ID") @PathVariable Long id) throws IOException {
        return ResponseEntity.ok(AppResponse.of(companyThemeService.deleteLogo(id, userDetails.getUsername())));
    }

    @Operation(summary = "Serve company logo", description = "Public — the login page and favicon load it before/without a token. "
            + "Cached for a year; the theme's logoUrl carries a version param that changes on every upload.")
    @ApiResponse(responseCode = "200", description = "Logo image")
    @ApiResponse(responseCode = "404", description = "No logo")
    @SecurityRequirements
    @GetMapping("/company-logos/{companyId}/{filename}")
    public ResponseEntity<Resource> serveLogo(@Parameter(description = "Company ID") @PathVariable Long companyId,
                                              @Parameter(description = "logo.png / logo.jpg / logo.webp") @PathVariable String filename) {
        return companyThemeService.resolveLogoFile(companyId, filename)
                .map(file -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic())
                        .header("X-Content-Type-Options", "nosniff")
                        .contentType(filename.endsWith(".png") ? MediaType.IMAGE_PNG
                                : filename.endsWith(".webp") ? MediaType.parseMediaType("image/webp")
                                : MediaType.IMAGE_JPEG)
                        .<Resource>body(new FileSystemResource(file)))
                .orElse(ResponseEntity.notFound().build());
    }
}
