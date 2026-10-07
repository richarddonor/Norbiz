package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.cache.CacheRegion;
import com.chardizard.Norbiz.cache.CacheScopeResolver;
import com.chardizard.Norbiz.cache.QueryCache;
import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.BillOfMaterialRequest;
import com.chardizard.Norbiz.dto.BillOfMaterialResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.BillOfMaterial;
import com.chardizard.Norbiz.services.BillOfMaterialService;
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

@Tag(name = "Bills of Materials", description = "Bill of materials management — requires VIEW_BILL_OF_MATERIAL / CREATE_BILL_OF_MATERIAL / UPDATE_BILL_OF_MATERIAL / DELETE_BILL_OF_MATERIAL permissions. " +
        "A bill of materials lists the raw-material components (quantity per unit) needed to assemble one unit of an output item.")
@RestController
@RequestMapping("/bills-of-materials")
@RequiredArgsConstructor
public class BillOfMaterialController {

    private final BillOfMaterialService billOfMaterialService;
    private final QueryCache queryCache;
    private final CacheScopeResolver cacheScopes;

    @Operation(summary = "List bill of materialss", description = "Returns bill of materialss belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "List returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_BILL_OF_MATERIAL permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_BILL_OF_MATERIAL')")
    public ResponseEntity<AppResponse<PageResponse<BillOfMaterialResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by code (contains)") @RequestParam(required = false) String code,
            @Parameter(description = "Filter by output item code or name (contains)") @RequestParam(required = false) String item,
            @Parameter(description = "Filter by company name (contains)") @RequestParam(required = false) String company,
            @Parameter(description = "Filter by creator (contains)") @RequestParam(required = false) String createdBy,
            @Parameter(description = "Filter by active status (true or false)") @RequestParam(required = false) String active,
            @Parameter(description = "Filter by last-updated date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtFrom,
            @Parameter(description = "Filter by last-updated date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtTo,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(code)) filters.put("code", code);
        if (StringUtils.hasText(item)) filters.put("item", item);
        if (StringUtils.hasText(company)) filters.put("company", company);
        if (StringUtils.hasText(createdBy)) filters.put("createdBy", createdBy);
        if (StringUtils.hasText(active)) filters.put("active", active);

        Instant fromInstant = DateRangeUtils.startOfDayUtc(updatedAtFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(updatedAtTo);

        var page = queryCache.page(CacheRegion.LIST_BILL_OF_MATERIAL, cacheScopes.forUser(userDetails.getUsername()),
                QueryCache.params("filters", filters, "updatedAtFrom", fromInstant, "updatedAtTo", toInstant), pageable, BillOfMaterialResponse.class,
                () -> billOfMaterialService.findAllForUser(userDetails.getUsername(), filters, fromInstant, toInstant, pageable).map(this::toResponse));
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(page)));
    }

    @Operation(summary = "Get bill of materials by ID")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_BILL_OF_MATERIAL permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_BILL_OF_MATERIAL')")
    public ResponseEntity<AppResponse<BillOfMaterialResponse>> getById(@Parameter(description = "bill of materials ID") @PathVariable Long id,
                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(billOfMaterialService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Create bill of materials", description = "Code must be unique within the company. The output and every component must be INVENTORY items of the company; " +
            "a component can't be the output itself or appear twice. Components are replaced wholesale on update.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "Validation failed or duplicate within the company")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_BILL_OF_MATERIAL permission")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_BILL_OF_MATERIAL')")
    public ResponseEntity<AppResponse<BillOfMaterialResponse>> create(@Valid @RequestBody BillOfMaterialRequest request,
                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(billOfMaterialService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Update bill of materials")
    @ApiResponse(responseCode = "200", description = "Updated")
    @ApiResponse(responseCode = "400", description = "Validation failed or duplicate within the company")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_BILL_OF_MATERIAL permission")
    @ApiResponse(responseCode = "404", description = "Not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_BILL_OF_MATERIAL')")
    public ResponseEntity<AppResponse<BillOfMaterialResponse>> update(@Parameter(description = "bill of materials ID") @PathVariable Long id,
                                                            @Valid @RequestBody BillOfMaterialRequest request,
                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(billOfMaterialService.update(id, request, userDetails.getUsername()))));
    }

    @Operation(summary = "Delete bill of materials", description = "Rejected while any Assembly references it; deactivate it instead.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "403", description = "Missing DELETE_BILL_OF_MATERIAL permission")
    @ApiResponse(responseCode = "404", description = "Not found")
    @ApiResponse(responseCode = "409", description = "Still used by other records (code ENTITY_IN_USE)")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE_BILL_OF_MATERIAL')")
    public ResponseEntity<Void> delete(@Parameter(description = "bill of materials ID") @PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        billOfMaterialService.delete(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    private BillOfMaterialResponse toResponse(BillOfMaterial bom) {
        BillOfMaterialResponse res = new BillOfMaterialResponse();
        res.setId(bom.getId());
        res.setCompanyId(bom.getCompany().getId());
        res.setCompanyName(bom.getCompany().getName());
        res.setCode(bom.getCode());
        res.setItemId(bom.getItem().getId());
        res.setItemCode(bom.getItem().getItemCode());
        res.setItemName(bom.getItem().getName());
        res.setActive(bom.isActive());
        res.setComponents(bom.getComponents().stream().map(l -> {
            BillOfMaterialResponse.Component c = new BillOfMaterialResponse.Component();
            c.setId(l.getId());
            c.setLineNumber(l.getLineNumber());
            c.setItemId(l.getItem().getId());
            c.setItemCode(l.getItem().getItemCode());
            c.setItemName(l.getItem().getName());
            c.setQuantity(l.getQuantity());
            return c;
        }).toList());
        res.setCreatedAt(bom.getCreatedAt());
        res.setUpdatedAt(bom.getUpdatedAt());
        res.setCreatedBy(bom.getCreatedBy());
        res.setUpdatedBy(bom.getUpdatedBy());
        return res;
    }
}
