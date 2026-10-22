package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.DetailedReportFilter;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.dto.TransactionDetailedReportRow;
import com.chardizard.Norbiz.models.DetailedReportType;
import com.chardizard.Norbiz.services.TransactionDetailedReportService;
import com.chardizard.Norbiz.util.ListSort;
import com.chardizard.Norbiz.util.ListTotals;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestParam;
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

    private static final String DESCRIPTION = "Line items with their transaction header, newest transaction first (by posting time), lines in entry order; " +
            "?sort= takes company, referenceNumber, sheetNumber, date, warehouse, counterparty, itemCode, itemName, lineNumber, quantity or voided. " +
            "Scoped to the caller's companies (SUPER_ADMIN sees all). Cost prices/amounts on purchase reports are null without VIEW_COST_PRICE.";

    private static final String ACCOUNTING_DESCRIPTION = "One row per document line (memo line, payment tender, application, refund line, deposited "
            + "item, reconciliation line, statement line) with its transaction header, newest transaction first; description says what the "
            + "line is and amount is its amount (item fields are null). ?sort= takes company, referenceNumber, sheetNumber, date, counterparty, "
            + "lineNumber, amount or voided. Item and warehouse filters are rejected. Scoped to the caller's companies (SUPER_ADMIN sees all).";

    private final TransactionDetailedReportService reportService;

    @Operation(summary = "Inventory Adjustment - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_INVENTORY_ADJUSTMENT_DETAILED_REPORT permission")
    @GetMapping("/inventory-adjustments")
    @PreAuthorize("hasAuthority('VIEW_INVENTORY_ADJUSTMENT_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> inventoryAdjustments(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.INVENTORY_ADJUSTMENT, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Outlet Receipt - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_RECEIPT_DETAILED_REPORT permission")
    @GetMapping("/outlet-receipts")
    @PreAuthorize("hasAuthority('VIEW_OUTLET_RECEIPT_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> outletReceipts(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.OUTLET_RECEIPT, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Purchase Order - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PURCHASE_ORDER_DETAILED_REPORT permission")
    @GetMapping("/purchase-orders")
    @PreAuthorize("hasAuthority('VIEW_PURCHASE_ORDER_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> purchaseOrders(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.PURCHASE_ORDER, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Purchase Invoice - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PURCHASE_INVOICE_DETAILED_REPORT permission")
    @GetMapping("/purchase-invoices")
    @PreAuthorize("hasAuthority('VIEW_PURCHASE_INVOICE_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> purchaseInvoices(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.PURCHASE_INVOICE, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Purchase Receipt - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PURCHASE_RECEIPT_DETAILED_REPORT permission")
    @GetMapping("/purchase-receipts")
    @PreAuthorize("hasAuthority('VIEW_PURCHASE_RECEIPT_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> purchaseReceipts(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.PURCHASE_RECEIPT, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Delivery Receipt - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_DELIVERY_RECEIPT_DETAILED_REPORT permission")
    @GetMapping("/delivery-receipts")
    @PreAuthorize("hasAuthority('VIEW_DELIVERY_RECEIPT_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> deliveryReceipts(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.DELIVERY_RECEIPT, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Outlet Delivery Receipt - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_DELIVERY_RECEIPT_DETAILED_REPORT permission")
    @GetMapping("/outlet-delivery-receipts")
    @PreAuthorize("hasAuthority('VIEW_OUTLET_DELIVERY_RECEIPT_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> outletDeliveryReceipts(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.OUTLET_DELIVERY_RECEIPT, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Outlet Delivery Return - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_DELIVERY_RETURN_DETAILED_REPORT permission")
    @GetMapping("/outlet-delivery-returns")
    @PreAuthorize("hasAuthority('VIEW_OUTLET_DELIVERY_RETURN_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> outletDeliveryReturns(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.OUTLET_DELIVERY_RETURN, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Stock Transfer - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_STOCK_TRANSFER_DETAILED_REPORT permission")
    @GetMapping("/stock-transfers")
    @PreAuthorize("hasAuthority('VIEW_STOCK_TRANSFER_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> stockTransfers(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.STOCK_TRANSFER, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Outlet Pull-out - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_PULL_OUT_DETAILED_REPORT permission")
    @GetMapping("/outlet-pull-outs")
    @PreAuthorize("hasAuthority('VIEW_OUTLET_PULL_OUT_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> outletPullOuts(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.OUTLET_PULL_OUT, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Pull-out Receipt - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PULL_OUT_RECEIPT_DETAILED_REPORT permission")
    @GetMapping("/pull-out-receipts")
    @PreAuthorize("hasAuthority('VIEW_PULL_OUT_RECEIPT_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> pullOutReceipts(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.PULL_OUT_RECEIPT, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Assembly - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ASSEMBLY_DETAILED_REPORT permission")
    @GetMapping("/assemblies")
    @PreAuthorize("hasAuthority('VIEW_ASSEMBLY_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> assemblies(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.ASSEMBLY, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Sales Invoice - Detailed", description = DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_SALES_INVOICE_DETAILED_REPORT permission")
    @GetMapping("/sales-invoices")
    @PreAuthorize("hasAuthority('VIEW_SALES_INVOICE_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> salesInvoices(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.SALES_INVOICE, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Customer Memo - Detailed", description = ACCOUNTING_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_CUSTOMER_MEMO_DETAILED_REPORT permission")
    @GetMapping("/customer-memos")
    @PreAuthorize("hasAuthority('VIEW_CUSTOMER_MEMO_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> customerMemos(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.CUSTOMER_MEMO, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Supplier Memo - Detailed", description = ACCOUNTING_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_SUPPLIER_MEMO_DETAILED_REPORT permission")
    @GetMapping("/supplier-memos")
    @PreAuthorize("hasAuthority('VIEW_SUPPLIER_MEMO_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> supplierMemos(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.SUPPLIER_MEMO, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Customer Payment - Detailed", description = ACCOUNTING_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_CUSTOMER_PAYMENT_DETAILED_REPORT permission")
    @GetMapping("/customer-payments")
    @PreAuthorize("hasAuthority('VIEW_CUSTOMER_PAYMENT_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> customerPayments(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.CUSTOMER_PAYMENT, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Supplier Payment - Detailed", description = ACCOUNTING_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_SUPPLIER_PAYMENT_DETAILED_REPORT permission")
    @GetMapping("/supplier-payments")
    @PreAuthorize("hasAuthority('VIEW_SUPPLIER_PAYMENT_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> supplierPayments(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.SUPPLIER_PAYMENT, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Customer Refund - Detailed", description = ACCOUNTING_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_CUSTOMER_REFUND_DETAILED_REPORT permission")
    @GetMapping("/customer-refunds")
    @PreAuthorize("hasAuthority('VIEW_CUSTOMER_REFUND_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> customerRefunds(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.CUSTOMER_REFUND, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Supplier Refund - Detailed", description = ACCOUNTING_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_SUPPLIER_REFUND_DETAILED_REPORT permission")
    @GetMapping("/supplier-refunds")
    @PreAuthorize("hasAuthority('VIEW_SUPPLIER_REFUND_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> supplierRefunds(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.SUPPLIER_REFUND, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Deposit - Detailed", description = ACCOUNTING_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_DEPOSIT_DETAILED_REPORT permission")
    @GetMapping("/deposits")
    @PreAuthorize("hasAuthority('VIEW_DEPOSIT_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> deposits(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.DEPOSIT, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Bank Reconciliation - Detailed", description = ACCOUNTING_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_BANK_RECONCILIATION_DETAILED_REPORT permission")
    @GetMapping("/bank-reconciliations")
    @PreAuthorize("hasAuthority('VIEW_BANK_RECONCILIATION_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> bankReconciliations(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.BANK_RECONCILIATION, userDetails, filter, pageable, totals);
    }

    @Operation(summary = "Statement of Account - Detailed", description = ACCOUNTING_DESCRIPTION)
    @ApiResponse(responseCode = "200", description = "Report rows returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_STATEMENT_OF_ACCOUNT_DETAILED_REPORT permission")
    @GetMapping("/statements-of-account")
    @PreAuthorize("hasAuthority('VIEW_STATEMENT_OF_ACCOUNT_DETAILED_REPORT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> statementsOfAccount(
            @AuthenticationPrincipal UserDetails userDetails, @Valid @ParameterObject DetailedReportFilter filter, @ParameterObject Pageable pageable,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals) {
        return report(DetailedReportType.STATEMENT_OF_ACCOUNT, userDetails, filter, pageable, totals);
    }

    private ResponseEntity<AppResponse<PageResponse<TransactionDetailedReportRow>>> report(
            DetailedReportType type, UserDetails userDetails, DetailedReportFilter filter, Pageable pageable, boolean totals) {
        boolean canViewCostPrice = userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("VIEW_COST_PRICE"));
        var rows = reportService.find(type, userDetails.getUsername(), filter, canViewCostPrice, pageable);
        var response = PageResponse.of(rows);
        if (totals) {
            response.withTotals(ListTotals.within(rows.getTotalElements(), () -> reportService
                    .find(type, userDetails.getUsername(), filter, canViewCostPrice, ListSort.unpaged(pageable)).getContent(),
                    ListTotals.column("quantity", TransactionDetailedReportRow::getQuantity),
                    ListTotals.column("quantityLoaded", TransactionDetailedReportRow::getQuantityLoaded),
                    ListTotals.column("amount", TransactionDetailedReportRow::getAmount)));
        }
        return ResponseEntity.ok(AppResponse.of(response));
    }
}
