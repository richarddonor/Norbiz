package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "Companies", description = "Company lookup — restricted to SUPER_ADMIN (MANAGE_SYSTEM permission)")
@RestController
@RequestMapping("/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyRepository companyRepository;

    @Operation(summary = "List companies", description = "Returns all active companies. Used to populate company selectors in admin UI.")
    @ApiResponse(responseCode = "200", description = "Company list returned")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE_SYSTEM permission")
    @GetMapping
    @PreAuthorize("hasAuthority('MANAGE_SYSTEM')")
    public ResponseEntity<List<Map<String, Object>>> getAll() {
        List<Map<String, Object>> companies = companyRepository.findAll().stream()
                .map(c -> Map.<String, Object>of("id", c.getId(), "name", c.getName()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(companies);
    }
}
