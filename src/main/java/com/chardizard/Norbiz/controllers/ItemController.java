package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.ItemRequest;
import com.chardizard.Norbiz.dto.ItemResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.Item;
import com.chardizard.Norbiz.models.PriceType;
import com.chardizard.Norbiz.services.ItemService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "Items", description = "Catalog item management — requires VIEW_ITEM / CREATE_ITEM / UPDATE_ITEM / DELETE_ITEM permissions. " +
        "All write operations are scoped to the company identified by the X-Company-Id request header.")
@RestController
@RequestMapping("/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService itemService;

    @Operation(summary = "List items", description = "Returns items belonging to the caller's accessible companies. SUPER_ADMIN sees all items.")
    @ApiResponse(responseCode = "200", description = "Item list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ITEM permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_ITEM')")
    public ResponseEntity<AppResponse<PageResponse<ItemResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by item code (contains)") @RequestParam(required = false) String itemCode,
            @Parameter(description = "Filter by name (contains)") @RequestParam(required = false) String name,
            @Parameter(description = "Filter by category name (contains)") @RequestParam(required = false) String category,
            @Parameter(description = "Filter by company name (contains)") @RequestParam(required = false) String company,
            @Parameter(description = "Filter by SKU code (contains)") @RequestParam(required = false) String skus,
            @Parameter(description = "Filter by unit price (contains)") @RequestParam(required = false) String unitPrice,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(itemCode)) filters.put("itemCode", itemCode);
        if (StringUtils.hasText(name)) filters.put("name", name);
        if (StringUtils.hasText(category)) filters.put("category", category);
        if (StringUtils.hasText(company)) filters.put("company", company);
        if (StringUtils.hasText(skus)) filters.put("skus", skus);
        if (StringUtils.hasText(unitPrice)) filters.put("unitPrice", unitPrice);

        var items = itemService.findAllForUser(userDetails.getUsername(), filters, pageable)
                .map(item -> toResponse(item, userDetails));
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(items)));
    }

    @Operation(summary = "Create item", description = "Creates a new catalog item under the specified company. Item code must be unique within the company.")
    @ApiResponse(responseCode = "201", description = "Item created")
    @ApiResponse(responseCode = "400", description = "Item code already exists for this company")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_ITEM permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_ITEM')")
    public ResponseEntity<AppResponse<ItemResponse>> create(@Valid @RequestBody ItemRequest request,
                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(itemService.create(request, userDetails.getUsername(), hasCostPriceAuthority(userDetails)), userDetails)));
    }

    @Operation(summary = "Update item", description = "Replaces the item's name, SKUs, and prices. Item code and image are immutable via this endpoint.")
    @ApiResponse(responseCode = "200", description = "Item updated")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_ITEM permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Item not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_ITEM')")
    public ResponseEntity<AppResponse<ItemResponse>> update(@Parameter(description = "Item ID") @PathVariable Long id,
                                                            @Valid @RequestBody ItemRequest request,
                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(itemService.update(id, request, userDetails.getUsername(), hasCostPriceAuthority(userDetails)), userDetails)));
    }

    @Operation(summary = "Delete item", description = "Permanently deletes an item and all its SKUs, prices, and image record.")
    @ApiResponse(responseCode = "204", description = "Item deleted")
    @ApiResponse(responseCode = "403", description = "Missing DELETE_ITEM permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Item not found")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE_ITEM')")
    public ResponseEntity<Void> delete(@Parameter(description = "Item ID") @PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        itemService.delete(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    private boolean hasCostPriceAuthority(UserDetails userDetails) {
        return userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("VIEW_COST_PRICE"));
    }

    private ItemResponse toResponse(Item item, UserDetails userDetails) {
        boolean canViewCostPrice = hasCostPriceAuthority(userDetails);

        ItemResponse res = new ItemResponse();
        res.setId(item.getId());
        res.setCompanyId(item.getCompany().getId());
        res.setCompanyName(item.getCompany().getName());
        res.setItemCategoryId(item.getItemCategory().getId());
        res.setItemCategoryName(item.getItemCategory().getName());
        res.setItemCode(item.getItemCode());
        res.setName(item.getName());
        res.setImagePath(item.getImagePath());
        res.setActive(item.isActive());
        res.setSkus(item.getSkus().stream()
                .map(s -> s.getSkuCode())
                .collect(Collectors.toList()));
        res.setPrices(item.getPrices().stream()
                .filter(p -> canViewCostPrice || p.getPriceType() != PriceType.COST_PRICE)
                .map(p -> {
                    ItemResponse.PriceEntry entry = new ItemResponse.PriceEntry();
                    entry.setPriceType(p.getPriceType());
                    entry.setAmount(p.getAmount());
                    return entry;
                })
                .collect(Collectors.toList()));
        res.setTags(item.getTags());
        return res;
    }
}