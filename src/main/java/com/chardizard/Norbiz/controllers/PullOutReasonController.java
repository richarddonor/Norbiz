package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.cache.CacheRegion;
import com.chardizard.Norbiz.cache.CacheScopeResolver;
import com.chardizard.Norbiz.cache.QueryCache;
import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.PullOutReasonRequest;
import com.chardizard.Norbiz.dto.PullOutReasonResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.PullOutReason;
import com.chardizard.Norbiz.services.PullOutReasonService;
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

@Tag(name = "Pull Out Reasons", description = "Pull out reason management — requires VIEW_PULL_OUT_REASON / CREATE_PULL_OUT_REASON / UPDATE_PULL_OUT_REASON / DELETE_PULL_OUT_REASON permissions.")
@RestController
@RequestMapping("/pull-out-reasons")
@RequiredArgsConstructor
public class PullOutReasonController {

    private final PullOutReasonService pullOutReasonService;
    private final QueryCache queryCache;
    private final CacheScopeResolver cacheScopes;

    @Operation(summary = "List pull out reasons", description = "Returns pull out reasons belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "List returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PULL_OUT_REASON permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_PULL_OUT_REASON')")
    public ResponseEntity<AppResponse<PageResponse<PullOutReasonResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by name (contains)") @RequestParam(required = false) String name,
            @Parameter(description = "Filter by company name (contains)") @RequestParam(required = false) String company,
            @Parameter(description = "Filter by creator (contains)") @RequestParam(required = false) String createdBy,
            @Parameter(description = "Filter by active status (true or false)") @RequestParam(required = false) String active,
            @Parameter(description = "Filter by last-updated date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtFrom,
            @Parameter(description = "Filter by last-updated date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtTo,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(name)) filters.put("name", name);
        if (StringUtils.hasText(company)) filters.put("company", company);
        if (StringUtils.hasText(createdBy)) filters.put("createdBy", createdBy);
        if (StringUtils.hasText(active)) filters.put("active", active);

        Instant fromInstant = DateRangeUtils.startOfDayUtc(updatedAtFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(updatedAtTo);

        var page = queryCache.page(CacheRegion.LIST_PULL_OUT_REASON, cacheScopes.forUser(userDetails.getUsername()),
                QueryCache.params("filters", filters, "updatedAtFrom", fromInstant, "updatedAtTo", toInstant), pageable, PullOutReasonResponse.class,
                () -> pullOutReasonService.findAllForUser(userDetails.getUsername(), filters, fromInstant, toInstant, pageable).map(this::toResponse));
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(page)));
    }

    @Operation(summary = "Get pull out reason by ID")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PULL_OUT_REASON permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_PULL_OUT_REASON')")
    public ResponseEntity<AppResponse<PullOutReasonResponse>> getById(@Parameter(description = "pull out reason ID") @PathVariable Long id,
                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(pullOutReasonService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Create pull out reason", description = "Name must be unique within the company.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "Validation failed or duplicate within the company")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_PULL_OUT_REASON permission")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_PULL_OUT_REASON')")
    public ResponseEntity<AppResponse<PullOutReasonResponse>> create(@Valid @RequestBody PullOutReasonRequest request,
                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(pullOutReasonService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Update pull out reason")
    @ApiResponse(responseCode = "200", description = "Updated")
    @ApiResponse(responseCode = "400", description = "Validation failed or duplicate within the company")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_PULL_OUT_REASON permission")
    @ApiResponse(responseCode = "404", description = "Not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_PULL_OUT_REASON')")
    public ResponseEntity<AppResponse<PullOutReasonResponse>> update(@Parameter(description = "pull out reason ID") @PathVariable Long id,
                                                            @Valid @RequestBody PullOutReasonRequest request,
                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(pullOutReasonService.update(id, request, userDetails.getUsername()))));
    }

    @Operation(summary = "Delete pull out reason", description = "Rejected while any Outlet Pull Out uses it; deactivate it instead.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "403", description = "Missing DELETE_PULL_OUT_REASON permission")
    @ApiResponse(responseCode = "404", description = "Not found")
    @ApiResponse(responseCode = "409", description = "Still used by other records (code ENTITY_IN_USE)")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE_PULL_OUT_REASON')")
    public ResponseEntity<Void> delete(@Parameter(description = "pull out reason ID") @PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        pullOutReasonService.delete(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    private PullOutReasonResponse toResponse(PullOutReason reason) {
        PullOutReasonResponse res = new PullOutReasonResponse();
        res.setId(reason.getId());
        res.setCompanyId(reason.getCompany().getId());
        res.setCompanyName(reason.getCompany().getName());
        res.setName(reason.getName());
        res.setActive(reason.isActive());
        res.setCreatedAt(reason.getCreatedAt());
        res.setUpdatedAt(reason.getUpdatedAt());
        res.setCreatedBy(reason.getCreatedBy());
        res.setUpdatedBy(reason.getUpdatedBy());
        return res;
    }
}
