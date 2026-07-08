package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.ItemSkuRequest;
import com.chardizard.Norbiz.dto.ItemSkuResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.ItemSku;
import com.chardizard.Norbiz.services.ItemSkuService;
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
import java.util.Map;

@Tag(name = "Item SKUs", description = "Item SKU management — requires VIEW_ITEM / CREATE_ITEM / UPDATE_ITEM / DELETE_ITEM permissions.")
@RestController
@RequestMapping("/item-skus")
@RequiredArgsConstructor
public class ItemSkuController {

    private final ItemSkuService itemSkuService;

    @Operation(summary = "List SKUs", description = "Returns all SKUs accessible to the caller. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "SKU list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ITEM permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_ITEM')")
    public ResponseEntity<AppResponse<PageResponse<ItemSkuResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by SKU code (contains)") @RequestParam(required = false) String skuCode,
            @Parameter(description = "Filter by item code (contains)") @RequestParam(required = false) String itemCode,
            @Parameter(description = "Filter by item name (contains)") @RequestParam(required = false) String itemName,
            @Parameter(description = "Filter by unit price (contains)") @RequestParam(required = false) String unitPrice,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(skuCode)) filters.put("skuCode", skuCode);
        if (StringUtils.hasText(itemCode)) filters.put("itemCode", itemCode);
        if (StringUtils.hasText(itemName)) filters.put("itemName", itemName);
        if (StringUtils.hasText(unitPrice)) filters.put("unitPrice", unitPrice);

        var skus = itemSkuService.findAll(userDetails.getUsername(), filters, pageable).map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(skus)));
    }

    @Operation(summary = "Get SKU", description = "Returns a single SKU by ID.")
    @ApiResponse(responseCode = "200", description = "SKU returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ITEM permission or no access to company")
    @ApiResponse(responseCode = "404", description = "SKU not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_ITEM')")
    public ResponseEntity<AppResponse<ItemSkuResponse>> getById(@Parameter(description = "SKU ID") @PathVariable Long id,
                                                                @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(itemSkuService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Create SKU", description = "Creates a new SKU for an item. skuCode must be globally unique. itemId, skuCode and unitPrice are required.")
    @ApiResponse(responseCode = "201", description = "SKU created")
    @ApiResponse(responseCode = "400", description = "Validation error or duplicate SKU code")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_ITEM permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_ITEM')")
    public ResponseEntity<AppResponse<ItemSkuResponse>> create(@Valid @RequestBody ItemSkuRequest request,
                                                               @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(itemSkuService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Update SKU", description = "Updates the skuCode and unitPrice of an existing SKU. Both fields are required.")
    @ApiResponse(responseCode = "200", description = "SKU updated")
    @ApiResponse(responseCode = "400", description = "Validation error or duplicate SKU code")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_ITEM permission or no access to company")
    @ApiResponse(responseCode = "404", description = "SKU not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_ITEM')")
    public ResponseEntity<AppResponse<ItemSkuResponse>> update(@Parameter(description = "SKU ID") @PathVariable Long id,
                                                               @Valid @RequestBody ItemSkuRequest request,
                                                               @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(itemSkuService.update(id, request, userDetails.getUsername()))));
    }

    @Operation(summary = "Delete SKU", description = "Permanently deletes a SKU.")
    @ApiResponse(responseCode = "204", description = "SKU deleted")
    @ApiResponse(responseCode = "403", description = "Missing DELETE_ITEM permission or no access to company")
    @ApiResponse(responseCode = "404", description = "SKU not found")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE_ITEM')")
    public ResponseEntity<Void> delete(@Parameter(description = "SKU ID") @PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        itemSkuService.delete(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    private ItemSkuResponse toResponse(ItemSku sku) {
        ItemSkuResponse res = new ItemSkuResponse();
        res.setId(sku.getId());
        res.setItemId(sku.getItem().getId());
        res.setItemCode(sku.getItem().getItemCode());
        res.setItemName(sku.getItem().getName());
        res.setSkuCode(sku.getSkuCode());
        res.setUnitPrice(sku.getUnitPrice());
        return res;
    }
}