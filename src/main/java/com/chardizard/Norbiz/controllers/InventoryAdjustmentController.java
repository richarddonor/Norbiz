package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.InventoryAdjustmentLineResponse;
import com.chardizard.Norbiz.dto.InventoryAdjustmentRequest;
import com.chardizard.Norbiz.dto.InventoryAdjustmentResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.InventoryAdjustment;
import com.chardizard.Norbiz.models.InventoryAdjustmentLine;
import com.chardizard.Norbiz.services.InventoryAdjustmentService;
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

@Tag(name = "Inventory Adjustments", description = "Inventory adjustment transactions — requires VIEW_INVENTORY_ADJUSTMENT / CREATE_INVENTORY_ADJUSTMENT / VOID_INVENTORY_ADJUSTMENT permissions. " +
        "Adjustments are immutable once posted: only create, view, and void are supported.")
@RestController
@RequestMapping("/inventory-adjustments")
@RequiredArgsConstructor
public class InventoryAdjustmentController {

    private final InventoryAdjustmentService inventoryAdjustmentService;

    @Operation(summary = "List inventory adjustments", description = "Returns adjustments belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Adjustment list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_INVENTORY_ADJUSTMENT permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_INVENTORY_ADJUSTMENT')")
    public ResponseEntity<AppResponse<PageResponse<InventoryAdjustmentResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by warehouse ID") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by adjustment date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by adjustment date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);

        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var adjustments = inventoryAdjustmentService.findAllForUser(userDetails.getUsername(), warehouseId, filters, fromInstant, toInstant, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(adjustments)));
    }

    @Operation(summary = "Get inventory adjustment by ID")
    @ApiResponse(responseCode = "200", description = "Adjustment returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_INVENTORY_ADJUSTMENT permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Adjustment not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_INVENTORY_ADJUSTMENT')")
    public ResponseEntity<AppResponse<InventoryAdjustmentResponse>> getById(@Parameter(description = "Adjustment ID") @PathVariable Long id,
                                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(inventoryAdjustmentService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Post inventory adjustment", description = "Creates and posts an inventory adjustment: each line must reference an item tagged INVENTORY " +
            "and a non-zero quantity delta. Posting immediately writes ledger entries and updates the running balance for each line's item+warehouse.")
    @ApiResponse(responseCode = "201", description = "Adjustment posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. item not inventory-tracked, zero quantity, mismatched company)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_INVENTORY_ADJUSTMENT permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_INVENTORY_ADJUSTMENT')")
    public ResponseEntity<AppResponse<InventoryAdjustmentResponse>> create(@Valid @RequestBody InventoryAdjustmentRequest request,
                                                                           @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(inventoryAdjustmentService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Void inventory adjustment", description = "Cancels an adjustment by reversing every ledger entry it posted. " +
            "Fails if already voided or if the adjustment has been loaded (fully or partially) into a downstream transaction.")
    @ApiResponse(responseCode = "200", description = "Adjustment voided")
    @ApiResponse(responseCode = "400", description = "Already voided, or has been loaded")
    @ApiResponse(responseCode = "403", description = "Missing VOID_INVENTORY_ADJUSTMENT permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_INVENTORY_ADJUSTMENT')")
    public ResponseEntity<AppResponse<InventoryAdjustmentResponse>> voidAdjustment(@Parameter(description = "Adjustment ID") @PathVariable Long id,
                                                                                    @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(inventoryAdjustmentService.voidAdjustment(id, userDetails.getUsername()))));
    }

    private InventoryAdjustmentResponse toResponse(InventoryAdjustment adjustment) {
        InventoryAdjustmentResponse res = new InventoryAdjustmentResponse();
        res.setId(adjustment.getId());
        res.setCompanyId(adjustment.getCompany().getId());
        res.setCompanyName(adjustment.getCompany().getName());
        res.setWarehouseId(adjustment.getWarehouse().getId());
        res.setWarehouseName(adjustment.getWarehouse().getName());
        res.setReferenceNumber(adjustment.getReferenceNumber());
        res.setSheetNumber(adjustment.getSheetNumber());
        res.setAdjustmentDate(adjustment.getAdjustmentDate());
        res.setReason(adjustment.getReason());
        res.setCreatedAt(adjustment.getCreatedAt());
        res.setCreatedBy(adjustment.getCreatedBy());
        res.setVoided(adjustment.isVoided());
        res.setVoidedAt(adjustment.getVoidedAt());
        res.setVoidedBy(adjustment.getVoidedBy());
        res.setLoaded(adjustment.isLoaded());
        res.setLines(adjustment.getLines().stream().map(this::toLineResponse).collect(Collectors.toList()));
        return res;
    }

    private InventoryAdjustmentLineResponse toLineResponse(InventoryAdjustmentLine line) {
        InventoryAdjustmentLineResponse res = new InventoryAdjustmentLineResponse();
        res.setId(line.getId());
        res.setItemId(line.getItem().getId());
        res.setItemCode(line.getItem().getItemCode());
        res.setItemName(line.getItem().getName());
        res.setQuantity(line.getQuantity());
        res.setQuantityLoaded(line.getQuantityLoaded());
        return res;
    }
}