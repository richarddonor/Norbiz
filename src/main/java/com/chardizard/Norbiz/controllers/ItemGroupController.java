package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.cache.CacheRegion;
import com.chardizard.Norbiz.cache.CacheScopeResolver;
import com.chardizard.Norbiz.cache.QueryCache;
import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.ItemGroupRequest;
import com.chardizard.Norbiz.dto.ItemGroupResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.ItemGroup;
import com.chardizard.Norbiz.services.ItemGroupService;
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

@Tag(name = "Item Groups", description = "Item group management — requires VIEW_ITEM_GROUP / CREATE_ITEM_GROUP / UPDATE_ITEM_GROUP / DELETE_ITEM_GROUP permissions.")
@RestController
@RequestMapping("/item-groups")
@RequiredArgsConstructor
public class ItemGroupController {

    private final ItemGroupService itemGroupService;
    private final QueryCache queryCache;
    private final CacheScopeResolver cacheScopes;

    @Operation(summary = "List item groups", description = "Returns groups belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Item group list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ITEM_GROUP permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_ITEM_GROUP')")
    public ResponseEntity<AppResponse<PageResponse<ItemGroupResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by name (contains)") @RequestParam(required = false) String name,
            @Parameter(description = "Filter by description (contains)") @RequestParam(required = false) String description,
            @Parameter(description = "Filter by BN initials (contains)") @RequestParam(required = false) String bnInitials,
            @Parameter(description = "Filter by company name (contains)") @RequestParam(required = false) String company,
            @Parameter(description = "Filter by creator (contains)") @RequestParam(required = false) String createdBy,
            @Parameter(description = "Filter by active status (true or false)") @RequestParam(required = false) String active,
            @Parameter(description = "Filter by last-updated date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtFrom,
            @Parameter(description = "Filter by last-updated date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtTo,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(name)) filters.put("name", name);
        if (StringUtils.hasText(description)) filters.put("description", description);
        if (StringUtils.hasText(bnInitials)) filters.put("bnInitials", bnInitials);
        if (StringUtils.hasText(company)) filters.put("company", company);
        if (StringUtils.hasText(createdBy)) filters.put("createdBy", createdBy);
        if (StringUtils.hasText(active)) filters.put("active", active);

        Instant fromInstant = DateRangeUtils.startOfDayUtc(updatedAtFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(updatedAtTo);

        var groups = queryCache.page(CacheRegion.LIST_ITEM_GROUP, cacheScopes.forUser(userDetails.getUsername()),
                QueryCache.params("filters", filters, "updatedAtFrom", fromInstant, "updatedAtTo", toInstant), pageable, ItemGroupResponse.class,
                () -> itemGroupService.findAllForUser(userDetails.getUsername(), filters, fromInstant, toInstant, pageable).map(this::toResponse));
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(groups)));
    }

    @Operation(summary = "Get item group by ID")
    @ApiResponse(responseCode = "200", description = "Item group returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ITEM_GROUP permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Item group not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_ITEM_GROUP')")
    public ResponseEntity<AppResponse<ItemGroupResponse>> getById(@Parameter(description = "Item group ID") @PathVariable Long id,
                                                                  @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(itemGroupService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Create item group", description = "Item group name must be unique within the company. Commission rates are percentages (0–100).")
    @ApiResponse(responseCode = "201", description = "Item group created")
    @ApiResponse(responseCode = "400", description = "Validation failed or item group name already exists for this company")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_ITEM_GROUP permission")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_ITEM_GROUP')")
    public ResponseEntity<AppResponse<ItemGroupResponse>> create(@Valid @RequestBody ItemGroupRequest request,
                                                                 @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(itemGroupService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Update item group")
    @ApiResponse(responseCode = "200", description = "Item group updated")
    @ApiResponse(responseCode = "400", description = "Validation failed or item group name already exists for this company")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_ITEM_GROUP permission")
    @ApiResponse(responseCode = "404", description = "Item group not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_ITEM_GROUP')")
    public ResponseEntity<AppResponse<ItemGroupResponse>> update(@Parameter(description = "Item group ID") @PathVariable Long id,
                                                                 @Valid @RequestBody ItemGroupRequest request,
                                                                 @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(itemGroupService.update(id, request, userDetails.getUsername()))));
    }

    @Operation(summary = "Delete item group", description = "Rejected while any item is still assigned to the group.")
    @ApiResponse(responseCode = "204", description = "Item group deleted")
    @ApiResponse(responseCode = "400", description = "Item group is still assigned to items")
    @ApiResponse(responseCode = "403", description = "Missing DELETE_ITEM_GROUP permission")
    @ApiResponse(responseCode = "404", description = "Item group not found")
    @ApiResponse(responseCode = "409", description = "Still used by other records (code ENTITY_IN_USE)")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE_ITEM_GROUP')")
    public ResponseEntity<Void> delete(@Parameter(description = "Item group ID") @PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        itemGroupService.delete(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    private ItemGroupResponse toResponse(ItemGroup group) {
        ItemGroupResponse res = new ItemGroupResponse();
        res.setId(group.getId());
        res.setCompanyId(group.getCompany().getId());
        res.setCompanyName(group.getCompany().getName());
        res.setName(group.getName());
        res.setDescription(group.getDescription());
        res.setBnInitials(group.getBnInitials());
        res.setCommissionRate(group.getCommissionRate());
        res.setFocalCommissionRate(group.getFocalCommissionRate());
        res.setActive(group.isActive());
        res.setCreatedAt(group.getCreatedAt());
        res.setUpdatedAt(group.getUpdatedAt());
        res.setCreatedBy(group.getCreatedBy());
        res.setUpdatedBy(group.getUpdatedBy());
        return res;
    }
}
