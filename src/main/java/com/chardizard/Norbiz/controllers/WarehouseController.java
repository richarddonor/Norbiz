package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.dto.WarehouseRequest;
import com.chardizard.Norbiz.dto.WarehouseResponse;
import com.chardizard.Norbiz.models.Warehouse;
import com.chardizard.Norbiz.services.WarehouseService;
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

@Tag(name = "Warehouses", description = "Warehouse management — requires VIEW_WAREHOUSE / CREATE_WAREHOUSE / UPDATE_WAREHOUSE / DELETE_WAREHOUSE permissions.")
@RestController
@RequestMapping("/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;

    @Operation(summary = "List warehouses", description = "Returns warehouses belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Warehouse list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_WAREHOUSE permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_WAREHOUSE')")
    public ResponseEntity<AppResponse<PageResponse<WarehouseResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by name (contains)") @RequestParam(required = false) String name,
            @Parameter(description = "Filter by code (contains)") @RequestParam(required = false) String code,
            @Parameter(description = "Filter by company name (contains)") @RequestParam(required = false) String company,
            @Parameter(description = "Filter by creator (contains)") @RequestParam(required = false) String createdBy,
            @Parameter(description = "Filter by last-updated date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtFrom,
            @Parameter(description = "Filter by last-updated date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtTo,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(name)) filters.put("name", name);
        if (StringUtils.hasText(code)) filters.put("code", code);
        if (StringUtils.hasText(company)) filters.put("company", company);
        if (StringUtils.hasText(createdBy)) filters.put("createdBy", createdBy);

        Instant fromInstant = DateRangeUtils.startOfDayUtc(updatedAtFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(updatedAtTo);

        var warehouses = warehouseService.findAllForUser(userDetails.getUsername(), filters, fromInstant, toInstant, pageable).map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(warehouses)));
    }

    @Operation(summary = "Get warehouse by ID")
    @ApiResponse(responseCode = "200", description = "Warehouse returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_WAREHOUSE permission")
    @ApiResponse(responseCode = "404", description = "Warehouse not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_WAREHOUSE')")
    public ResponseEntity<AppResponse<WarehouseResponse>> getById(@Parameter(description = "Warehouse ID") @PathVariable Long id) {
        return ResponseEntity.ok(AppResponse.of(toResponse(warehouseService.findById(id))));
    }

    @Operation(summary = "Create warehouse", description = "Warehouse code (if supplied) must be unique per company.")
    @ApiResponse(responseCode = "201", description = "Warehouse created")
    @ApiResponse(responseCode = "400", description = "Warehouse code already exists for this company")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_WAREHOUSE permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_WAREHOUSE')")
    public ResponseEntity<AppResponse<WarehouseResponse>> create(@Valid @RequestBody WarehouseRequest request,
                                                                 @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(warehouseService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Update warehouse")
    @ApiResponse(responseCode = "200", description = "Warehouse updated")
    @ApiResponse(responseCode = "400", description = "Warehouse code already exists for this company")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_WAREHOUSE permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Warehouse not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_WAREHOUSE')")
    public ResponseEntity<AppResponse<WarehouseResponse>> update(@Parameter(description = "Warehouse ID") @PathVariable Long id,
                                                                 @Valid @RequestBody WarehouseRequest request,
                                                                 @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(warehouseService.update(id, request, userDetails.getUsername()))));
    }

    @Operation(summary = "Delete warehouse")
    @ApiResponse(responseCode = "204", description = "Warehouse deleted")
    @ApiResponse(responseCode = "403", description = "Missing DELETE_WAREHOUSE permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Warehouse not found")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE_WAREHOUSE')")
    public ResponseEntity<Void> delete(@Parameter(description = "Warehouse ID") @PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        warehouseService.delete(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    private WarehouseResponse toResponse(Warehouse warehouse) {
        WarehouseResponse res = new WarehouseResponse();
        res.setId(warehouse.getId());
        res.setCompanyId(warehouse.getCompany().getId());
        res.setCompanyName(warehouse.getCompany().getName());
        res.setCode(warehouse.getCode());
        res.setName(warehouse.getName());
        res.setActive(warehouse.isActive());
        res.setCreatedAt(warehouse.getCreatedAt());
        res.setUpdatedAt(warehouse.getUpdatedAt());
        res.setCreatedBy(warehouse.getCreatedBy());
        res.setUpdatedBy(warehouse.getUpdatedBy());
        return res;
    }
}