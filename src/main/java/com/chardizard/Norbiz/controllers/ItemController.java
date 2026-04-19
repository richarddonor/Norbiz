package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.ItemRequest;
import com.chardizard.Norbiz.dto.ItemResponse;
import com.chardizard.Norbiz.models.Item;
import com.chardizard.Norbiz.services.ItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    public ResponseEntity<List<ItemResponse>> getAll(@AuthenticationPrincipal UserDetails userDetails) {
        List<ItemResponse> items = itemService.findAllForUser(userDetails.getUsername())
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(items);
    }

    @Operation(summary = "Create item", description = "Creates a new catalog item under the specified company. Item code must be unique within the company.")
    @ApiResponse(responseCode = "201", description = "Item created")
    @ApiResponse(responseCode = "400", description = "Item code already exists for this company")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_ITEM permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_ITEM')")
    public ResponseEntity<ItemResponse> create(@RequestBody ItemRequest request,
                                               @AuthenticationPrincipal UserDetails userDetails) {
        Item item = itemService.create(request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(item));
    }

    @Operation(summary = "Update item", description = "Replaces the item's name, SKUs, and prices. Item code and image are immutable via this endpoint.")
    @ApiResponse(responseCode = "200", description = "Item updated")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_ITEM permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Item not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_ITEM')")
    public ResponseEntity<ItemResponse> update(@Parameter(description = "Item ID") @PathVariable Long id,
                                               @RequestBody ItemRequest request,
                                               @AuthenticationPrincipal UserDetails userDetails) {
        Item item = itemService.update(id, request, userDetails.getUsername());
        return ResponseEntity.ok(toResponse(item));
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

    private ItemResponse toResponse(Item item) {
        ItemResponse res = new ItemResponse();
        res.setId(item.getId());
        res.setCompanyId(item.getCompany().getId());
        res.setCompanyName(item.getCompany().getName());
        res.setItemCode(item.getItemCode());
        res.setName(item.getName());
        res.setImagePath(item.getImagePath());
        res.setActive(item.isActive());
        res.setSkus(item.getSkus().stream()
                .map(s -> s.getSkuCode())
                .collect(Collectors.toList()));
        res.setPrices(item.getPrices().stream()
                .map(p -> {
                    ItemResponse.PriceEntry entry = new ItemResponse.PriceEntry();
                    entry.setPriceType(p.getPriceType());
                    entry.setAmount(p.getAmount());
                    return entry;
                })
                .collect(Collectors.toList()));
        return res;
    }
}
