package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.dto.SupplierRequest;
import com.chardizard.Norbiz.dto.SupplierResponse;
import com.chardizard.Norbiz.models.Supplier;
import com.chardizard.Norbiz.services.SupplierService;
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

@Tag(name = "Suppliers", description = "Supplier management — requires VIEW_SUPPLIER / CREATE_SUPPLIER / UPDATE_SUPPLIER / DELETE_SUPPLIER permissions.")
@RestController
@RequestMapping("/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    @Operation(summary = "List suppliers", description = "Returns suppliers belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Supplier list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_SUPPLIER permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_SUPPLIER')")
    public ResponseEntity<AppResponse<PageResponse<SupplierResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by name (contains)") @RequestParam(required = false) String name,
            @Parameter(description = "Filter by code (contains)") @RequestParam(required = false) String code,
            @Parameter(description = "Filter by company name (contains)") @RequestParam(required = false) String company,
            @Parameter(description = "Filter by creator (contains)") @RequestParam(required = false) String createdBy,
            @Parameter(description = "Filter by active status (true or false)") @RequestParam(required = false) String active,
            @Parameter(description = "Filter by last-updated date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtFrom,
            @Parameter(description = "Filter by last-updated date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtTo,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(name)) filters.put("name", name);
        if (StringUtils.hasText(code)) filters.put("code", code);
        if (StringUtils.hasText(company)) filters.put("company", company);
        if (StringUtils.hasText(createdBy)) filters.put("createdBy", createdBy);
        if (StringUtils.hasText(active)) filters.put("active", active);

        Instant fromInstant = DateRangeUtils.startOfDayUtc(updatedAtFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(updatedAtTo);

        var suppliers = supplierService.findAllForUser(userDetails.getUsername(), filters, fromInstant, toInstant, pageable).map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(suppliers)));
    }

    @Operation(summary = "Get supplier by ID")
    @ApiResponse(responseCode = "200", description = "Supplier returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_SUPPLIER permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Supplier not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_SUPPLIER')")
    public ResponseEntity<AppResponse<SupplierResponse>> getById(@Parameter(description = "Supplier ID") @PathVariable Long id,
                                                                 @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(supplierService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Create supplier", description = "Supplier code (if supplied) must be unique per company.")
    @ApiResponse(responseCode = "201", description = "Supplier created")
    @ApiResponse(responseCode = "400", description = "Supplier code already exists for this company")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_SUPPLIER permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_SUPPLIER')")
    public ResponseEntity<AppResponse<SupplierResponse>> create(@Valid @RequestBody SupplierRequest request,
                                                                @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(supplierService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Update supplier")
    @ApiResponse(responseCode = "200", description = "Supplier updated")
    @ApiResponse(responseCode = "400", description = "Supplier code already exists for this company")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_SUPPLIER permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Supplier not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_SUPPLIER')")
    public ResponseEntity<AppResponse<SupplierResponse>> update(@Parameter(description = "Supplier ID") @PathVariable Long id,
                                                                @Valid @RequestBody SupplierRequest request,
                                                                @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(supplierService.update(id, request, userDetails.getUsername()))));
    }

    @Operation(summary = "Delete supplier")
    @ApiResponse(responseCode = "204", description = "Supplier deleted")
    @ApiResponse(responseCode = "403", description = "Missing DELETE_SUPPLIER permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Supplier not found")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE_SUPPLIER')")
    public ResponseEntity<Void> delete(@Parameter(description = "Supplier ID") @PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        supplierService.delete(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    private SupplierResponse toResponse(Supplier supplier) {
        SupplierResponse res = new SupplierResponse();
        res.setId(supplier.getId());
        res.setCompanyId(supplier.getCompany().getId());
        res.setCompanyName(supplier.getCompany().getName());
        res.setCode(supplier.getCode());
        res.setName(supplier.getName());
        res.setEmail(supplier.getEmail());
        res.setPhone(supplier.getPhone());
        res.setActive(supplier.isActive());
        res.setCreatedAt(supplier.getCreatedAt());
        res.setUpdatedAt(supplier.getUpdatedAt());
        res.setCreatedBy(supplier.getCreatedBy());
        res.setUpdatedBy(supplier.getUpdatedBy());
        return res;
    }
}