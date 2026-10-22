package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.AccountSide;
import com.chardizard.Norbiz.models.AgingBasis;
import com.chardizard.Norbiz.services.AccountingReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

// Not a list controller (no SORTABLE): the reports are computed in full for one company.
@Tag(name = "Accounting Reports", description = "AR/AP aging, customer/supplier ledgers (VIEW_AR_REPORT / VIEW_AP_REPORT) and the bank book "
        + "(VIEW_BANK_BOOK), all as of a date for one company. See docs/ACCOUNTING.md \"Reports\".")
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
@Validated
public class AccountingReportController {

    private static final String DATE = "^\\d{4}-\\d{2}-\\d{2}$";

    private final AccountingReportService reportService;

    @Operation(summary = "Accounts receivable aging", description = "Open customer charges as of asOf in Current / 1-30 / 31-60 / 61-90 / 91-120 / "
            + "120+ buckets (by due date, or document date), less unapplied credits, per customer. detail=true lists the items.")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_AR_REPORT permission or no access to company")
    @GetMapping("/ar-aging")
    @PreAuthorize("hasAuthority('VIEW_AR_REPORT')")
    public ResponseEntity<AppResponse<AgingReportResponse>> arAging(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Company ID") @RequestParam Long companyId,
            @Parameter(description = "As-of date (yyyy-MM-dd)") @RequestParam @Pattern(regexp = DATE) String asOf,
            @Parameter(description = "Age by DUE_DATE (default) or DOCUMENT_DATE") @RequestParam(defaultValue = "DUE_DATE") AgingBasis basis,
            @Parameter(description = "One customer only") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Include the items behind each row") @RequestParam(defaultValue = "false") boolean detail) {
        return ResponseEntity.ok(AppResponse.of(reportService.aging(AccountSide.RECEIVABLE, companyId, asOf, basis, customerId, detail,
                userDetails.getUsername())));
    }

    @Operation(summary = "Accounts payable aging", description = "Open supplier payables as of asOf in Current / 1-30 / 31-60 / 61-90 / 91-120 / 120+ "
            + "buckets, less unapplied advances and debit memos, per supplier. detail=true lists the items.")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_AP_REPORT permission or no access to company")
    @GetMapping("/ap-aging")
    @PreAuthorize("hasAuthority('VIEW_AP_REPORT')")
    public ResponseEntity<AppResponse<AgingReportResponse>> apAging(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Company ID") @RequestParam Long companyId,
            @Parameter(description = "As-of date (yyyy-MM-dd)") @RequestParam @Pattern(regexp = DATE) String asOf,
            @Parameter(description = "Age by DUE_DATE (default) or DOCUMENT_DATE") @RequestParam(defaultValue = "DUE_DATE") AgingBasis basis,
            @Parameter(description = "One supplier only") @RequestParam(required = false) Long supplierId,
            @Parameter(description = "Include the items behind each row") @RequestParam(defaultValue = "false") boolean detail) {
        return ResponseEntity.ok(AppResponse.of(reportService.aging(AccountSide.PAYABLE, companyId, asOf, basis, supplierId, detail,
                userDetails.getUsername())));
    }

    @Operation(summary = "Customer ledger", description = "Opening balance, every charge and credit in the period (and bounced checks reversing "
            + "their credit), with a running balance of what the customer owes.")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_AR_REPORT permission or no access to company")
    @GetMapping("/customer-ledger")
    @PreAuthorize("hasAuthority('VIEW_AR_REPORT')")
    public ResponseEntity<AppResponse<LedgerResponse>> customerLedger(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Company ID") @RequestParam Long companyId,
            @Parameter(description = "Customer ID") @RequestParam Long customerId,
            @Parameter(description = "Period start (yyyy-MM-dd)") @RequestParam @Pattern(regexp = DATE) String dateFrom,
            @Parameter(description = "Period end (yyyy-MM-dd)") @RequestParam @Pattern(regexp = DATE) String dateTo) {
        return ResponseEntity.ok(AppResponse.of(reportService.ledger(AccountSide.RECEIVABLE, companyId, customerId, dateFrom, dateTo,
                userDetails.getUsername())));
    }

    @Operation(summary = "Supplier ledger", description = "Opening balance, every payable and payment/credit in the period (and cancelled checks "
            + "reversing their credit), with a running balance of what we owe the supplier.")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_AP_REPORT permission or no access to company")
    @GetMapping("/supplier-ledger")
    @PreAuthorize("hasAuthority('VIEW_AP_REPORT')")
    public ResponseEntity<AppResponse<LedgerResponse>> supplierLedger(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Company ID") @RequestParam Long companyId,
            @Parameter(description = "Supplier ID") @RequestParam Long supplierId,
            @Parameter(description = "Period start (yyyy-MM-dd)") @RequestParam @Pattern(regexp = DATE) String dateFrom,
            @Parameter(description = "Period end (yyyy-MM-dd)") @RequestParam @Pattern(regexp = DATE) String dateTo) {
        return ResponseEntity.ok(AppResponse.of(reportService.ledger(AccountSide.PAYABLE, companyId, supplierId, dateFrom, dateTo,
                userDetails.getUsername())));
    }

    @Operation(summary = "Bank book", description = "A bank account's book entries in the period with a running balance (opening balance + entries).")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_BANK_BOOK permission or no access to company")
    @GetMapping("/bank-book")
    @PreAuthorize("hasAuthority('VIEW_BANK_BOOK')")
    public ResponseEntity<AppResponse<BankBookResponse>> bankBook(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Bank account ID") @RequestParam Long bankAccountId,
            @Parameter(description = "Period start (yyyy-MM-dd)") @RequestParam @Pattern(regexp = DATE) String dateFrom,
            @Parameter(description = "Period end (yyyy-MM-dd)") @RequestParam @Pattern(regexp = DATE) String dateTo) {
        return ResponseEntity.ok(AppResponse.of(reportService.bankBook(bankAccountId, dateFrom, dateTo, userDetails.getUsername())));
    }
}
