package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.ItemCategoryRequest;
import com.chardizard.Norbiz.dto.ItemCategoryResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.ItemCategory;
import com.chardizard.Norbiz.services.ItemCategoryService;
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

@Tag(name = "Item Categories", description = "Item category management — requires VIEW_ITEM_CATEGORY / CREATE_ITEM_CATEGORY / UPDATE_ITEM_CATEGORY / DELETE_ITEM_CATEGORY permissions.")
@RestController
@RequestMapping("/item-categories")
@RequiredArgsConstructor
public class ItemCategoryController {

    private final ItemCategoryService itemCategoryService;

    @Operation(summary = "List item categories", description = "Returns categories belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Item category list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ITEM_CATEGORY permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_ITEM_CATEGORY')")
    public ResponseEntity<AppResponse<PageResponse<ItemCategoryResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by name (contains)") @RequestParam(required = false) String name,
            @Parameter(description = "Filter by company name (contains)") @RequestParam(required = false) String company,
            @Parameter(description = "Filter by creator (contains)") @RequestParam(required = false) String createdBy,
            @Parameter(description = "Filter by last-updated date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtFrom,
            @Parameter(description = "Filter by last-updated date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtTo,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(name)) filters.put("name", name);
        if (StringUtils.hasText(company)) filters.put("company", company);
        if (StringUtils.hasText(createdBy)) filters.put("createdBy", createdBy);

        Instant fromInstant = DateRangeUtils.startOfDayUtc(updatedAtFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(updatedAtTo);

        var categories = itemCategoryService.findAllForUser(userDetails.getUsername(), filters, fromInstant, toInstant, pageable).map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(categories)));
    }

    @Operation(summary = "Get item category by ID")
    @ApiResponse(responseCode = "200", description = "Item category returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ITEM_CATEGORY permission")
    @ApiResponse(responseCode = "404", description = "Item category not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_ITEM_CATEGORY')")
    public ResponseEntity<AppResponse<ItemCategoryResponse>> getById(@Parameter(description = "Item category ID") @PathVariable Long id) {
        return ResponseEntity.ok(AppResponse.of(toResponse(itemCategoryService.findById(id))));
    }

    @Operation(summary = "Create item category", description = "Item category name must be unique within the company.")
    @ApiResponse(responseCode = "201", description = "Item category created")
    @ApiResponse(responseCode = "400", description = "Item category name already exists for this company")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_ITEM_CATEGORY permission")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_ITEM_CATEGORY')")
    public ResponseEntity<AppResponse<ItemCategoryResponse>> create(@Valid @RequestBody ItemCategoryRequest request,
                                                                    @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(itemCategoryService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Update item category")
    @ApiResponse(responseCode = "200", description = "Item category updated")
    @ApiResponse(responseCode = "400", description = "Item category name already exists for this company")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_ITEM_CATEGORY permission")
    @ApiResponse(responseCode = "404", description = "Item category not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_ITEM_CATEGORY')")
    public ResponseEntity<AppResponse<ItemCategoryResponse>> update(@Parameter(description = "Item category ID") @PathVariable Long id,
                                                                    @Valid @RequestBody ItemCategoryRequest request,
                                                                    @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(itemCategoryService.update(id, request, userDetails.getUsername()))));
    }

    @Operation(summary = "Delete item category")
    @ApiResponse(responseCode = "204", description = "Item category deleted")
    @ApiResponse(responseCode = "403", description = "Missing DELETE_ITEM_CATEGORY permission")
    @ApiResponse(responseCode = "404", description = "Item category not found")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE_ITEM_CATEGORY')")
    public ResponseEntity<Void> delete(@Parameter(description = "Item category ID") @PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        itemCategoryService.delete(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    private ItemCategoryResponse toResponse(ItemCategory category) {
        ItemCategoryResponse res = new ItemCategoryResponse();
        res.setId(category.getId());
        res.setCompanyId(category.getCompany().getId());
        res.setCompanyName(category.getCompany().getName());
        res.setName(category.getName());
        res.setCreatedAt(category.getCreatedAt());
        res.setUpdatedAt(category.getUpdatedAt());
        res.setCreatedBy(category.getCreatedBy());
        res.setUpdatedBy(category.getUpdatedBy());
        return res;
    }
}