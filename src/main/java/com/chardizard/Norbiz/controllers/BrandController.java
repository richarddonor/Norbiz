package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.BrandRequest;
import com.chardizard.Norbiz.dto.BrandResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.Brand;
import com.chardizard.Norbiz.services.BrandService;
import com.chardizard.Norbiz.util.DateRangeUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "Brands", description = "Brand management — requires VIEW_BRAND / CREATE_BRAND / UPDATE_BRAND / DELETE_BRAND permissions.")
@RestController
@RequestMapping("/brands")
@RequiredArgsConstructor
public class BrandController {

    private final BrandService brandService;

    @Operation(summary = "List brands")
    @ApiResponse(responseCode = "200", description = "Brand list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_BRAND permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_BRAND')")
    public ResponseEntity<AppResponse<PageResponse<BrandResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by name (contains)") @RequestParam(required = false) String name,
            @Parameter(description = "Filter by creator (contains)") @RequestParam(required = false) String createdBy,
            @Parameter(description = "Filter by last-updated date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtFrom,
            @Parameter(description = "Filter by last-updated date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtTo,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(name)) filters.put("name", name);
        if (StringUtils.hasText(createdBy)) filters.put("createdBy", createdBy);

        Instant fromInstant = DateRangeUtils.startOfDayUtc(updatedAtFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(updatedAtTo);

        var brands = brandService.findAllForUser(userDetails.getUsername(), filters, fromInstant, toInstant, pageable).map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(brands)));
    }

    @Operation(summary = "Get brand by ID")
    @ApiResponse(responseCode = "200", description = "Brand returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_BRAND permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Brand not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_BRAND')")
    public ResponseEntity<AppResponse<BrandResponse>> getById(@Parameter(description = "Brand ID") @PathVariable Long id,
                                                              @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(brandService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Create brand", description = "Brand name must be unique per company.")
    @ApiResponse(responseCode = "201", description = "Brand created")
    @ApiResponse(responseCode = "400", description = "Brand name already exists for this company")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_BRAND permission")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_BRAND')")
    public ResponseEntity<AppResponse<BrandResponse>> create(@Valid @RequestBody BrandRequest request,
                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(brandService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Update brand")
    @ApiResponse(responseCode = "200", description = "Brand updated")
    @ApiResponse(responseCode = "400", description = "Brand name already exists for this company")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_BRAND permission")
    @ApiResponse(responseCode = "404", description = "Brand not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_BRAND')")
    public ResponseEntity<AppResponse<BrandResponse>> update(@Parameter(description = "Brand ID") @PathVariable Long id,
                                                             @Valid @RequestBody BrandRequest request,
                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(brandService.update(id, request, userDetails.getUsername()))));
    }

    @Operation(summary = "Delete brand")
    @ApiResponse(responseCode = "204", description = "Brand deleted")
    @ApiResponse(responseCode = "403", description = "Missing DELETE_BRAND permission")
    @ApiResponse(responseCode = "404", description = "Brand not found")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE_BRAND')")
    public ResponseEntity<Void> delete(@Parameter(description = "Brand ID") @PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        brandService.delete(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    private BrandResponse toResponse(Brand brand) {
        BrandResponse res = new BrandResponse();
        res.setId(brand.getId());
        res.setCompanyId(brand.getCompany().getId());
        res.setCompanyName(brand.getCompany().getName());
        res.setName(brand.getName());
        res.setCreatedAt(brand.getCreatedAt());
        res.setUpdatedAt(brand.getUpdatedAt());
        res.setCreatedBy(brand.getCreatedBy());
        res.setUpdatedBy(brand.getUpdatedBy());
        return res;
    }
}