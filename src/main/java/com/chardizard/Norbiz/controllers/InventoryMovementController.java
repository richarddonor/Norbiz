package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.InventoryMovementResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.InventoryMovement;
import com.chardizard.Norbiz.services.InventoryMovementService;
import com.chardizard.Norbiz.util.DateRangeUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@Tag(name = "Inventory Movements", description = "Inventory Ledger report — the drill-down transaction history behind the Inventory Balance report. Requires VIEW_INVENTORY_REPORT permission.")
@RestController
@RequestMapping("/inventory-movements")
@RequiredArgsConstructor
public class InventoryMovementController {

    private final InventoryMovementService inventoryMovementService;

    @Operation(summary = "Inventory Ledger report", description = "Returns the raw ledger entries (quantity/transit quantity deltas) that contributed to the Inventory Balance report.")
    @ApiResponse(responseCode = "200", description = "Ledger entries returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_INVENTORY_REPORT permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_INVENTORY_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<InventoryMovementResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by warehouse ID") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "Filter by item ID") @RequestParam(required = false) Long itemId,
            @Parameter(description = "Filter by movement date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by movement date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            Pageable pageable) {
        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var movements = inventoryMovementService.findAllForUser(userDetails.getUsername(), warehouseId, itemId, fromInstant, toInstant, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(movements)));
    }

    private InventoryMovementResponse toResponse(InventoryMovement movement) {
        InventoryMovementResponse res = new InventoryMovementResponse();
        res.setId(movement.getId());
        res.setCompanyId(movement.getCompany().getId());
        res.setCompanyName(movement.getCompany().getName());
        res.setItemId(movement.getItem().getId());
        res.setItemCode(movement.getItem().getItemCode());
        res.setItemName(movement.getItem().getName());
        res.setWarehouseId(movement.getWarehouse().getId());
        res.setWarehouseName(movement.getWarehouse().getName());
        res.setQuantityDelta(movement.getQuantityDelta());
        res.setTransitQuantityDelta(movement.getTransitQuantityDelta());
        res.setMovementDate(movement.getMovementDate());
        res.setSourceType(movement.getSourceType());
        res.setSourceId(movement.getSourceId());
        res.setReferenceNumber(movement.getReferenceNumber());
        res.setSheetNumber(movement.getSheetNumber());
        res.setNotes(movement.getNotes());
        res.setCreatedAt(movement.getCreatedAt());
        res.setCreatedBy(movement.getCreatedBy());
        return res;
    }
}