package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.dto.PurchaseOrderLineResponse;
import com.chardizard.Norbiz.dto.PurchaseOrderRequest;
import com.chardizard.Norbiz.dto.PurchaseOrderResponse;
import com.chardizard.Norbiz.models.PurchaseOrder;
import com.chardizard.Norbiz.models.PurchaseOrderLine;
import com.chardizard.Norbiz.services.PurchaseOrderService;
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
import java.util.stream.Collectors;

@Tag(name = "Purchase Orders", description = "Purchase order transactions — requires VIEW_PURCHASE_ORDER / CREATE_PURCHASE_ORDER / VOID_PURCHASE_ORDER permissions. " +
        "Orders are immutable once posted: only create, view, and void are supported. Line cost price is gated by VIEW_COST_PRICE.")
@RestController
@RequestMapping("/purchase-orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    @Operation(summary = "List purchase orders", description = "Returns orders belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Order list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PURCHASE_ORDER permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_PURCHASE_ORDER')")
    public ResponseEntity<AppResponse<PageResponse<PurchaseOrderResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by warehouse ID") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "Filter by supplier ID") @RequestParam(required = false) Long supplierId,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by order date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by order date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);

        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        boolean canViewCostPrice = hasCostPriceAuthority(userDetails);
        var orders = purchaseOrderService.findAllForUser(userDetails.getUsername(), warehouseId, supplierId,
                        filters, fromInstant, toInstant, pageable)
                .map(o -> toResponse(o, canViewCostPrice));
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(orders)));
    }

    @Operation(summary = "Get purchase order by ID")
    @ApiResponse(responseCode = "200", description = "Order returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PURCHASE_ORDER permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Order not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_PURCHASE_ORDER')")
    public ResponseEntity<AppResponse<PurchaseOrderResponse>> getById(@Parameter(description = "Order ID") @PathVariable Long id,
                                                                       @AuthenticationPrincipal UserDetails userDetails) {
        PurchaseOrder order = purchaseOrderService.findById(id, userDetails.getUsername());
        return ResponseEntity.ok(AppResponse.of(toResponse(order, hasCostPriceAuthority(userDetails))));
    }

    @Operation(summary = "Post purchase order", description = "Creates and posts a purchase order: each line must reference an item tagged INVENTORY " +
            "and a positive quantity. Posting immediately writes ledger entries and increases transit quantity for each line's item+warehouse. " +
            "Cost price is only honored for callers holding VIEW_COST_PRICE (forced to 0 otherwise) and, if omitted, is preloaded from the item's current cost price.")
    @ApiResponse(responseCode = "201", description = "Order posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. item not inventory-tracked, non-positive quantity, mismatched company)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_PURCHASE_ORDER permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_PURCHASE_ORDER')")
    public ResponseEntity<AppResponse<PurchaseOrderResponse>> create(@Valid @RequestBody PurchaseOrderRequest request,
                                                                      @AuthenticationPrincipal UserDetails userDetails) {
        boolean canViewCostPrice = hasCostPriceAuthority(userDetails);
        PurchaseOrder saved = purchaseOrderService.create(request, userDetails.getUsername(), canViewCostPrice);
        return ResponseEntity.status(HttpStatus.CREATED).body(AppResponse.of(toResponse(saved, canViewCostPrice)));
    }

    @Operation(summary = "Void purchase order", description = "Cancels an order by reversing the transit quantity it posted. " +
            "Fails if already voided or if the order has been loaded (fully or partially) into a downstream transaction.")
    @ApiResponse(responseCode = "200", description = "Order voided")
    @ApiResponse(responseCode = "400", description = "Already voided, or has been loaded")
    @ApiResponse(responseCode = "403", description = "Missing VOID_PURCHASE_ORDER permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_PURCHASE_ORDER')")
    public ResponseEntity<AppResponse<PurchaseOrderResponse>> voidOrder(@Parameter(description = "Order ID") @PathVariable Long id,
                                                                         @AuthenticationPrincipal UserDetails userDetails) {
        PurchaseOrder voided = purchaseOrderService.voidPurchaseOrder(id, userDetails.getUsername());
        return ResponseEntity.ok(AppResponse.of(toResponse(voided, hasCostPriceAuthority(userDetails))));
    }

    private boolean hasCostPriceAuthority(UserDetails userDetails) {
        return userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("VIEW_COST_PRICE"));
    }

    private PurchaseOrderResponse toResponse(PurchaseOrder order, boolean canViewCostPrice) {
        PurchaseOrderResponse res = new PurchaseOrderResponse();
        res.setId(order.getId());
        res.setCompanyId(order.getCompany().getId());
        res.setCompanyName(order.getCompany().getName());
        res.setWarehouseId(order.getWarehouse().getId());
        res.setWarehouseName(order.getWarehouse().getName());
        res.setSupplierId(order.getSupplier().getId());
        res.setSupplierName(order.getSupplier().getName());
        res.setReferenceNumber(order.getReferenceNumber());
        res.setSheetNumber(order.getSheetNumber());
        res.setOrderDate(order.getOrderDate());
        res.setRemarks(order.getRemarks());
        res.setCreatedAt(order.getCreatedAt());
        res.setCreatedBy(order.getCreatedBy());
        res.setVoided(order.isVoided());
        res.setVoidedAt(order.getVoidedAt());
        res.setVoidedBy(order.getVoidedBy());
        res.setLoaded(order.isLoaded());
        res.setLines(order.getLines().stream().map(l -> toLineResponse(l, canViewCostPrice)).collect(Collectors.toList()));
        return res;
    }

    private PurchaseOrderLineResponse toLineResponse(PurchaseOrderLine line, boolean canViewCostPrice) {
        PurchaseOrderLineResponse res = new PurchaseOrderLineResponse();
        res.setId(line.getId());
        res.setItemId(line.getItem().getId());
        res.setItemCode(line.getItem().getItemCode());
        res.setItemName(line.getItem().getName());
        res.setQuantity(line.getQuantity());
        res.setCostPrice(canViewCostPrice ? line.getCostPrice() : null);
        res.setQuantityLoaded(line.getQuantityLoaded());
        return res;
    }
}
