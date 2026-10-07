package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.DetailedReportFilter;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.dto.TransactionDetailedReportRow;
import com.chardizard.Norbiz.models.DetailedReportType;
import com.chardizard.Norbiz.services.TransactionDetailedReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "&lt;Transaction&gt; - Detailed" reports — one endpoint and one VIEW_&lt;TYPE&gt;_DETAILED_REPORT permission
 * per transaction type (see {@link DetailedReportType}). Rows are line items flattened with their header.
 */
@Tag(name = "Detailed Reports", description = "One '<Transaction> - Detailed' report per transaction type: every line item with its transaction header. " +
        "Each requires its own VIEW_<TYPE>_DETAILED_REPORT permission.")
@RestController
@RequestMapping("/reports/detailed")
@RequiredArgsConstructor
public class TransactionDetailedReportController {

    private static final String DESCRIPTION = "Line items with their transaction header, newest transaction first, lines in entry order. " +
            "Scoped to the caller's companies (SUPER_ADMIN sees all). Cost prices/amounts on purchase reports are null without VIEW_COST_PRICE.";

    private final TransactionDetailedReportService reportService;

    @Operation(summary = "Inventory Adjustment - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_INVENTORY_ADJUSTMENT_DETAILED_REPORT permission")
    @GetMapping("/inventory-adjustments")
    @PreAuthorize("hasAuthority('VIEW_INVENTORY_ADJUSTMENT_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> inventoryAdjustments(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable) {
        return report(DetailedReportType.INVENTORY_ADJUSTMENT, userDetails, filter, pageable);
    }

    @Operation(summary = "Outlet Receive - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_RECEIVE_DETAILED_REPORT permission")
    @GetMapping("/outlet-receives")
    @PreAuthorize("hasAuthority('VIEW_OUTLET_RECEIVE_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> outletReceives(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable) {
        return report(DetailedReportType.OUTLET_RECEIVE, userDetails, filter, pageable);
    }

    @Operation(summary = "Purchase Order - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PURCHASE_ORDER_DETAILED_REPORT permission")
    @GetMapping("/purchase-orders")
    @PreAuthorize("hasAuthority('VIEW_PURCHASE_ORDER_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> purchaseOrders(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable) {
        return report(DetailedReportType.PURCHASE_ORDER, userDetails, filter, pageable);
    }

    @Operation(summary = "Purchase Invoice - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PURCHASE_INVOICE_DETAILED_REPORT permission")
    @GetMapping("/purchase-invoices")
    @PreAuthorize("hasAuthority('VIEW_PURCHASE_INVOICE_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> purchaseInvoices(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable) {
        return report(DetailedReportType.PURCHASE_INVOICE, userDetails, filter, pageable);
    }

    @Operation(summary = "Purchase Receive - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PURCHASE_RECEIVE_DETAILED_REPORT permission")
    @GetMapping("/purchase-receives")
    @PreAuthorize("hasAuthority('VIEW_PURCHASE_RECEIVE_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> purchaseReceives(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable) {
        return report(DetailedReportType.PURCHASE_RECEIVE, userDetails, filter, pageable);
    }

    @Operation(summary = "Delivery Receipt - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_DELIVERY_RECEIPT_DETAILED_REPORT permission")
    @GetMapping("/delivery-receipts")
    @PreAuthorize("hasAuthority('VIEW_DELIVERY_RECEIPT_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> deliveryReceipts(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable) {
        return report(DetailedReportType.DELIVERY_RECEIPT, userDetails, filter, pageable);
    }

    @Operation(summary = "Outlet Delivery Receipt - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_DELIVERY_RECEIPT_DETAILED_REPORT permission")
    @GetMapping("/outlet-delivery-receipts")
    @PreAuthorize("hasAuthority('VIEW_OUTLET_DELIVERY_RECEIPT_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> outletDeliveryReceipts(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable) {
        return report(DetailedReportType.OUTLET_DELIVERY_RECEIPT, userDetails, filter, pageable);
    }

    @Operation(summary = "Outlet Delivery Return - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_DELIVERY_RETURN_DETAILED_REPORT permission")
    @GetMapping("/outlet-delivery-returns")
    @PreAuthorize("hasAuthority('VIEW_OUTLET_DELIVERY_RETURN_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> outletDeliveryReturns(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable) {
        return report(DetailedReportType.OUTLET_DELIVERY_RETURN, userDetails, filter, pageable);
    }

    @Operation(summary = "Stock Transfer - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_STOCK_TRANSFER_DETAILED_REPORT permission")
    @GetMapping("/stock-transfers")
    @PreAuthorize("hasAuthority('VIEW_STOCK_TRANSFER_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> stockTransfers(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable) {
        return report(DetailedReportType.STOCK_TRANSFER, userDetails, filter, pageable);
    }

    @Operation(summary = "Outlet Pull Out - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_PULL_OUT_DETAILED_REPORT permission")
    @GetMapping("/outlet-pull-outs")
    @PreAuthorize("hasAuthority('VIEW_OUTLET_PULL_OUT_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> outletPullOuts(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable) {
        return report(DetailedReportType.OUTLET_PULL_OUT, userDetails, filter, pageable);
    }

    @Operation(summary = "Pull Out Receive - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PULL_OUT_RECEIVE_DETAILED_REPORT permission")
    @GetMapping("/pull-out-receives")
    @PreAuthorize("hasAuthority('VIEW_PULL_OUT_RECEIVE_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> pullOutReceives(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable) {
        return report(DetailedReportType.PULL_OUT_RECEIVE, userDetails, filter, pageable);
    }

    @Operation(summary = "Assembly - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ASSEMBLY_DETAILED_REPORT permission")
    @GetMapping("/assemblies")
    @PreAuthorize("hasAuthority('VIEW_ASSEMBLY_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> assemblies(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable) {
        return report(DetailedReportType.ASSEMBLY, userDetails, filter, pageable);
    }

    private ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> report(
            DetailedReportType type, UserDetails userDetails, DetailedReportFilter filter, Pageable pageable) {
        boolean canViewCostPrice = userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("VIEW_COST_PRICE"));
        var rows = reportService.find(type, userDetails.getUsername(), filter, canViewCostPrice, pageable);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(rows)));
    }
}
