package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.cache.CacheRegion;
import com.chardizard.Norbiz.cache.CacheScopeResolver;
import com.chardizard.Norbiz.cache.QueryCache;
import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.ItemSkuRequest;
import com.chardizard.Norbiz.dto.ItemSkuResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.ItemSku;
import com.chardizard.Norbiz.models.PriceType;
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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "Item SKUs", description = "Item SKU management — requires VIEW_ITEM / CREATE_ITEM / UPDATE_ITEM / DELETE_ITEM permissions.")
@RestController
@RequestMapping("/item-skus")
@RequiredArgsConstructor
public class ItemSkuController {

    private final ItemSkuService itemSkuService;
    private final QueryCache queryCache;
    private final CacheScopeResolver cacheScopes;

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
            @Parameter(description = "Filter by brand ID (exact)") @RequestParam(required = false) Long brandId,
            @Parameter(description = "Filter by item category ID (exact)") @RequestParam(required = false) Long itemCategoryId,
            @Parameter(description = "Filter by unit price (exact, e.g. 749.00)") @RequestParam(required = false) BigDecimal price,
            @Parameter(description = "Filter by item category name (contains)") @RequestParam(required = false) String itemCategory,
            @Parameter(description = "Filter by brand name (contains)") @RequestParam(required = false) String brand,
            @Parameter(description = "Filter by price type (UNIT_PRICE or FOCAL_PRICE)") @RequestParam(required = false) PriceType priceType,
            @Parameter(description = "Filter by barcode (contains)") @RequestParam(required = false) String barcode,
            @Parameter(description = "Filter by store item code (contains)") @RequestParam(required = false) String storeItemCode,
            @Parameter(description = "Filter by active flag") @RequestParam(required = false) Boolean active,
            @Parameter(description = "true: only SKUs assigned to an item; false: only SKUs without one") @RequestParam(required = false) Boolean assigned,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(skuCode)) filters.put("skuCode", skuCode);
        if (StringUtils.hasText(itemCode)) filters.put("itemCode", itemCode);
        if (StringUtils.hasText(itemName)) filters.put("itemName", itemName);
        if (StringUtils.hasText(unitPrice)) filters.put("unitPrice", unitPrice);
        if (brandId != null) filters.put("brandId", brandId.toString());
        if (itemCategoryId != null) filters.put("itemCategoryId", itemCategoryId.toString());
        if (price != null) filters.put("price", price.toPlainString());
        if (StringUtils.hasText(itemCategory)) filters.put("itemCategory", itemCategory);
        if (StringUtils.hasText(brand)) filters.put("brand", brand);
        if (priceType != null) filters.put("priceType", priceType.name());
        if (StringUtils.hasText(barcode)) filters.put("barcode", barcode);
        if (StringUtils.hasText(storeItemCode)) filters.put("storeItemCode", storeItemCode);
        if (active != null) filters.put("active", active.toString());
        if (assigned != null) filters.put("assigned", assigned.toString());

        var skus = queryCache.page(CacheRegion.LIST_ITEM_SKU, cacheScopes.forUser(userDetails.getUsername()),
                QueryCache.params("filters", filters), pageable, ItemSkuResponse.class,
                () -> itemSkuService.findAll(userDetails.getUsername(), filters, pageable).map(this::toResponse));
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

    @Operation(summary = "Create SKU", description = "Creates a new SKU. skuCode and unitPrice are required. itemId is optional: without it the SKU stands on its own and companyId is required. skuCode may be shared with other items but must be unique within the item.")
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

    @Operation(summary = "Update SKU", description = "Updates an existing SKU. skuCode and unitPrice are required; the other fields are left unchanged when null (a blank string clears a text field). The item and company can't be changed.")
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
    @ApiResponse(responseCode = "409", description = "Still used by other records (code ENTITY_IN_USE)")
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
        res.setCompanyId(sku.getCompany().getId());
        if (sku.getItem() != null) {
            res.setItemId(sku.getItem().getId());
            res.setItemCode(sku.getItem().getItemCode());
            res.setItemName(sku.getItem().getName());
        }
        res.setSkuCode(sku.getSkuCode());
        res.setUnitPrice(sku.getUnitPrice());
        if (sku.getItemCategory() != null) {
            res.setItemCategoryId(sku.getItemCategory().getId());
            res.setItemCategoryName(sku.getItemCategory().getName());
        }
        if (sku.getBrand() != null) {
            res.setBrandId(sku.getBrand().getId());
            res.setBrandName(sku.getBrand().getName());
        }
        res.setPriceType(sku.getPriceType());
        res.setStoreItemCode(sku.getStoreItemCode());
        res.setBarcode(sku.getBarcode());
        res.setVendorPart(sku.getVendorPart());
        res.setRdsDescription(sku.getRdsDescription());
        res.setActive(sku.isActive());
        res.setRdsSku(sku.isRdsSku());
        res.setLandmarkSku(sku.isLandmarkSku());
        return res;
    }
}