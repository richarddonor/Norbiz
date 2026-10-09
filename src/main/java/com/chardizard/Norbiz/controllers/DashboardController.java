package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.DashboardWidget;
import com.chardizard.Norbiz.services.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Dashboard widgets — one VIEW_DASHBOARD_&lt;KEY&gt; permission per widget (see {@link DashboardWidget}).
 * Which widgets a user pins, and their order, is the user's own preference ({@code /me/preferences/dashboard.layout}).
 */
@Tag(name = "Dashboard", description = "Widget catalog and per-widget data for the user's dashboard. " +
        "Each widget requires its own VIEW_DASHBOARD_<KEY> permission and is scoped to one company.")
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private static final String COMPANY_HEADER = "X-Company-Id";
    private static final String COMPANY_DESC = "Company to report on (must be one of the caller's companies). "
            + "Defaults to the X-Company-Id header (the session's active company); one of the two is required.";
    private static final String AS_OF_DESC = "The caller's current date (yyyy-MM-dd), used for aging and periods. Defaults to today in UTC.";

    private final DashboardService dashboardService;

    @Operation(summary = "Available widgets",
            description = "The widgets the caller holds the permission for, in catalog order. A small fixed list, so not paginated.")
    @ApiResponse(responseCode = "200", description = "Widget catalog returned")
    @GetMapping("/widgets")
    public ResponseEntity<AppResponse<List<DashboardWidgetResponse>>> widgets(Authentication authentication) {
        return ResponseEntity.ok(AppResponse.of(dashboardService.catalog(authentication)));
    }

    @Operation(summary = "Pending Outlet Receives",
            description = "Outlet delivery receipts not voided and not yet fully received, as a backlog: totals, received %, aging, "
                    + "outstanding by outlet (top 8 plus Others) and the 8 oldest receipts. Values use the DR line selling price.")
    @ApiResponse(responseCode = "200", description = "Widget data returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_DASHBOARD_PENDING_OUTLET_RECEIVES or no access to company")
    @GetMapping("/widgets/pending-outlet-receives")
    @PreAuthorize("hasAuthority('VIEW_DASHBOARD_PENDING_OUTLET_RECEIVES')")
    public ResponseEntity<AppResponse<BacklogResponse>> pendingOutletReceives(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = AS_OF_DESC) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ResponseEntity.ok(AppResponse.of(
                dashboardService.pendingOutletReceives(userDetails.getUsername(), company(companyId, headerCompanyId), asOf)));
    }

    @Operation(summary = "Outlet Sales Pulse",
            description = "Outlet Delivery Receipt sales net of Outlet Delivery Returns for the last `days` days ending asOf: daily series, "
                    + "totals, the previous period's net for comparison, and the top 5 outlets and agents.")
    @ApiResponse(responseCode = "200", description = "Widget data returned")
    @ApiResponse(responseCode = "400", description = "days outside 7–365")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_DASHBOARD_OUTLET_SALES or no access to company")
    @GetMapping("/widgets/outlet-sales")
    @PreAuthorize("hasAuthority('VIEW_DASHBOARD_OUTLET_SALES')")
    public ResponseEntity<AppResponse<OutletSalesResponse>> outletSales(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Period length in days (7–365, default 30)") @RequestParam(defaultValue = "30") @Min(7) @Max(365) int days,
            @Parameter(description = AS_OF_DESC) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ResponseEntity.ok(AppResponse.of(
                dashboardService.outletSales(userDetails.getUsername(), company(companyId, headerCompanyId), days, asOf)));
    }

    @Operation(summary = "Pending Purchase Orders",
            description = "Purchase orders not voided, not invoiced and not fully received, as a backlog by supplier (breakdown: destination "
                    + "warehouse). Amounts are outstanding × line cost and are null without VIEW_COST_PRICE.")
    @ApiResponse(responseCode = "200", description = "Widget data returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_DASHBOARD_PENDING_PURCHASE_ORDERS or no access to company")
    @GetMapping("/widgets/pending-purchase-orders")
    @PreAuthorize("hasAuthority('VIEW_DASHBOARD_PENDING_PURCHASE_ORDERS')")
    public ResponseEntity<AppResponse<BacklogResponse>> pendingPurchaseOrders(
            Authentication authentication,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = AS_OF_DESC) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ResponseEntity.ok(AppResponse.of(dashboardService.pendingPurchaseOrders(
                authentication.getName(), company(companyId, headerCompanyId), asOf, canViewCostPrice(authentication))));
    }

    @Operation(summary = "Unpaid Purchase Invoices",
            description = "Purchase invoices not voided and not PAID, as a backlog by supplier (breakdown: payment status), aged from the "
                    + "invoice date. Amount = net payable (discounted lines, header discount, plus fees); null without VIEW_COST_PRICE.")
    @ApiResponse(responseCode = "200", description = "Widget data returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_DASHBOARD_UNPAID_PURCHASE_INVOICES or no access to company")
    @GetMapping("/widgets/unpaid-purchase-invoices")
    @PreAuthorize("hasAuthority('VIEW_DASHBOARD_UNPAID_PURCHASE_INVOICES')")
    public ResponseEntity<AppResponse<BacklogResponse>> unpaidPurchaseInvoices(
            Authentication authentication,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = AS_OF_DESC) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ResponseEntity.ok(AppResponse.of(dashboardService.unpaidPurchaseInvoices(
                authentication.getName(), company(companyId, headerCompanyId), asOf, canViewCostPrice(authentication))));
    }

    @Operation(summary = "Pull-outs Awaiting Receive",
            description = "Outlet pull-outs not voided and not fully received back, as a backlog by outlet (breakdown: pull-out reason). "
                    + "Values use the pull-out line's selling price.")
    @ApiResponse(responseCode = "200", description = "Widget data returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_DASHBOARD_PULL_OUTS_AWAITING_RECEIVE or no access to company")
    @GetMapping("/widgets/pull-outs-awaiting-receive")
    @PreAuthorize("hasAuthority('VIEW_DASHBOARD_PULL_OUTS_AWAITING_RECEIVE')")
    public ResponseEntity<AppResponse<BacklogResponse>> pullOutsAwaitingReceive(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = AS_OF_DESC) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ResponseEntity.ok(AppResponse.of(
                dashboardService.pullOutsAwaitingReceive(userDetails.getUsername(), company(companyId, headerCompanyId), asOf)));
    }

    @Operation(summary = "Stock in Transit",
            description = "Current in-transit vs on-hand quantity per warehouse (top 8 by transit plus Others), and totals by warehouse kind.")
    @ApiResponse(responseCode = "200", description = "Widget data returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_DASHBOARD_STOCK_IN_TRANSIT or no access to company")
    @GetMapping("/widgets/stock-in-transit")
    @PreAuthorize("hasAuthority('VIEW_DASHBOARD_STOCK_IN_TRANSIT')")
    public ResponseEntity<AppResponse<StockInTransitResponse>> stockInTransit(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId) {
        return ResponseEntity.ok(AppResponse.of(
                dashboardService.stockInTransit(userDetails.getUsername(), company(companyId, headerCompanyId))));
    }

    @Operation(summary = "Outlet Stock Health",
            description = "Days of cover (on-hand ÷ daily units sold over the last `days`) for every outlet/item pair the outlet sold: "
                    + "stock-out and low-cover counts, a grid of the 10 busiest outlets × 8 best-selling items, and the 8 worst pairs.")
    @ApiResponse(responseCode = "200", description = "Widget data returned")
    @ApiResponse(responseCode = "400", description = "days outside 7–90")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_DASHBOARD_OUTLET_STOCK_HEALTH or no access to company")
    @GetMapping("/widgets/outlet-stock-health")
    @PreAuthorize("hasAuthority('VIEW_DASHBOARD_OUTLET_STOCK_HEALTH')")
    public ResponseEntity<AppResponse<OutletStockHealthResponse>> outletStockHealth(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Sales window in days for the sales rate (7–90, default 30)") @RequestParam(defaultValue = "30") @Min(7) @Max(90) int days,
            @Parameter(description = AS_OF_DESC) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ResponseEntity.ok(AppResponse.of(
                dashboardService.outletStockHealth(userDetails.getUsername(), company(companyId, headerCompanyId), days, asOf)));
    }

    @Operation(summary = "Inventory Adjustment Trend",
            description = "Units added and removed by non-voided inventory adjustments per day over the last `days`, the top 5 warehouses "
                    + "by units moved, and the 5 most frequent reasons.")
    @ApiResponse(responseCode = "200", description = "Widget data returned")
    @ApiResponse(responseCode = "400", description = "days outside 7–365")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_DASHBOARD_INVENTORY_ADJUSTMENT_TREND or no access to company")
    @GetMapping("/widgets/inventory-adjustment-trend")
    @PreAuthorize("hasAuthority('VIEW_DASHBOARD_INVENTORY_ADJUSTMENT_TREND')")
    public ResponseEntity<AppResponse<InventoryAdjustmentTrendResponse>> inventoryAdjustmentTrend(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Period length in days (7–365, default 30)") @RequestParam(defaultValue = "30") @Min(7) @Max(365) int days,
            @Parameter(description = AS_OF_DESC) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ResponseEntity.ok(AppResponse.of(
                dashboardService.inventoryAdjustmentTrend(userDetails.getUsername(), company(companyId, headerCompanyId), days, asOf)));
    }

    @Operation(summary = "Agent Leaderboard",
            description = "Top 10 agents by net outlet sales (ODR − returns) over the last `days`, with gross, returns, document count, "
                    + "a zero-filled daily net series, and the previous period's net and rank.")
    @ApiResponse(responseCode = "200", description = "Widget data returned")
    @ApiResponse(responseCode = "400", description = "days outside 7–365")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_DASHBOARD_AGENT_LEADERBOARD or no access to company")
    @GetMapping("/widgets/agent-leaderboard")
    @PreAuthorize("hasAuthority('VIEW_DASHBOARD_AGENT_LEADERBOARD')")
    public ResponseEntity<AppResponse<AgentLeaderboardResponse>> agentLeaderboard(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Period length in days (7–365, default 30)") @RequestParam(defaultValue = "30") @Min(7) @Max(365) int days,
            @Parameter(description = AS_OF_DESC) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ResponseEntity.ok(AppResponse.of(
                dashboardService.agentLeaderboard(userDetails.getUsername(), company(companyId, headerCompanyId), days, asOf)));
    }

    @Operation(summary = "Transaction Activity",
            description = "Transactions posted (CREATED events) per calendar day for each transaction type over the last `days`, "
                    + "bucketed in the caller's time zone, plus void counts and the busiest day.")
    @ApiResponse(responseCode = "200", description = "Widget data returned")
    @ApiResponse(responseCode = "400", description = "days outside 7–90 or unknown time zone")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_DASHBOARD_TRANSACTION_ACTIVITY or no access to company")
    @GetMapping("/widgets/transaction-activity")
    @PreAuthorize("hasAuthority('VIEW_DASHBOARD_TRANSACTION_ACTIVITY')")
    public ResponseEntity<AppResponse<TransactionActivityResponse>> transactionActivity(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Period length in days (7–90, default 28)") @RequestParam(defaultValue = "28") @Min(7) @Max(90) int days,
            @Parameter(description = "The caller's current date in their time zone (yyyy-MM-dd). Defaults to today in `tz`.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @Parameter(description = "IANA time zone for day boundaries, e.g. Asia/Manila (default UTC)") @RequestParam(required = false) @Size(max = 64) String tz) {
        return ResponseEntity.ok(AppResponse.of(dashboardService.transactionActivity(
                userDetails.getUsername(), company(companyId, headerCompanyId), days, asOf, tz)));
    }

    private static boolean canViewCostPrice(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("VIEW_COST_PRICE"));
    }

    private static Long company(Long companyId, Long headerCompanyId) {
        return companyId != null ? companyId : headerCompanyId;
    }
}
