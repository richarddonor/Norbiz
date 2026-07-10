package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.InventoryBalanceResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.services.InventoryBalanceService;
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

@Tag(name = "Inventory Balances", description = "Inventory Balance report — current or as-of-date stock levels per item/warehouse. Requires VIEW_INVENTORY_REPORT permission.")
@RestController
@RequestMapping("/inventory-balances")
@RequiredArgsConstructor
public class InventoryBalanceController {

    private final InventoryBalanceService inventoryBalanceService;

    @Operation(summary = "Inventory Balance report", description = "Three modes, in priority order: (1) startDate+endDate — period report with " +
            "beginning/ending/net quantity per item/warehouse; (2) asOfDate — balance reconstructed from the ledger as of that date; " +
            "(3) neither — current stock from the live running balance. costPrice/value are only populated for callers with VIEW_COST_PRICE.")
    @ApiResponse(responseCode = "200", description = "Balance list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_INVENTORY_REPORT permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_INVENTORY_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<InventoryBalanceResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by warehouse ID") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "Filter by item ID") @RequestParam(required = false) Long itemId,
            @Parameter(description = "Reconstruct balance as of this date (yyyy-MM-dd) instead of the live running balance") @RequestParam(required = false) String asOfDate,
            @Parameter(description = "Period report: range start (yyyy-MM-dd, inclusive). Requires endDate too.") @RequestParam(required = false) String startDate,
            @Parameter(description = "Period report: range end (yyyy-MM-dd, inclusive). Requires startDate too.") @RequestParam(required = false) String endDate,
            Pageable pageable) {
        Instant asOfInstant = DateRangeUtils.endOfDayUtc(asOfDate);
        Instant rangeStart = DateRangeUtils.startOfDayUtc(startDate);
        Instant rangeEnd = DateRangeUtils.endOfDayUtc(endDate);
        boolean canViewCostPrice = userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("VIEW_COST_PRICE"));

        var balances = inventoryBalanceService.findBalances(userDetails.getUsername(), warehouseId, itemId,
                asOfInstant, rangeStart, rangeEnd, canViewCostPrice, pageable);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(balances)));
    }
}