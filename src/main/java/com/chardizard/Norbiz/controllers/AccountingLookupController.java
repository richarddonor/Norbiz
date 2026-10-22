package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.AccountSide;
import com.chardizard.Norbiz.models.OpenItemDirection;
import com.chardizard.Norbiz.services.AccountingLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

// AR/AP form dropdowns, under /lookups like the rest (see LookupController and LookupAccess).
@Tag(name = "Lookups", description = "Dropdown data (id/code/name only) for transaction and master-data forms. "
        + "Accessible with the entity's VIEW_ permission or any permission whose form needs it (see LookupAccess).")
@RestController
@RequestMapping("/lookups")
@RequiredArgsConstructor
@Validated
public class AccountingLookupController {

    private static final String COMPANY_HEADER = "X-Company-Id";
    private static final String COMPANY_DESC = "Company whose options to return (must be one of the caller's companies). "
            + "Defaults to the X-Company-Id header (the session's active company); one of the two is required.";

    private final AccountingLookupService lookupService;

    @Operation(summary = "Bank dropdown")
    @ApiResponse(responseCode = "200", description = "Bank options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/banks")
    @PreAuthorize("@lookupAccess.can(authentication, 'BANK')")
    public ResponseEntity<AppResponse<PageResponse<LookupResponse>>> banks(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Search text (contains) over code and name") @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = "Return only active records (default true)") @RequestParam(defaultValue = "true") boolean activeOnly,
            Pageable pageable) {
        return ok(lookupService.banks(userDetails.getUsername(), company(companyId, headerCompanyId), q, activeOnly, pageable));
    }

    @Operation(summary = "Bank option by ID")
    @ApiResponse(responseCode = "200", description = "Bank option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/banks/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'BANK')")
    public ResponseEntity<AppResponse<LookupResponse>> bank(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(lookupService.bank(id, userDetails.getUsername())));
    }

    @Operation(summary = "Bank account dropdown", description = "The company's own accounts.")
    @ApiResponse(responseCode = "200", description = "Bank account options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/bank-accounts")
    @PreAuthorize("@lookupAccess.can(authentication, 'BANK_ACCOUNT')")
    public ResponseEntity<AppResponse<PageResponse<BankAccountLookupResponse>>> bankAccounts(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Search text (contains) over account number, name and bank") @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = "Return only active records (default true)") @RequestParam(defaultValue = "true") boolean activeOnly,
            Pageable pageable) {
        return ok(lookupService.bankAccounts(userDetails.getUsername(), company(companyId, headerCompanyId), q, activeOnly, pageable));
    }

    @Operation(summary = "Bank account option by ID")
    @ApiResponse(responseCode = "200", description = "Bank account option returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/bank-accounts/{id}")
    @PreAuthorize("@lookupAccess.can(authentication, 'BANK_ACCOUNT')")
    public ResponseEntity<AppResponse<BankAccountLookupResponse>> bankAccount(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(lookupService.bankAccount(id, userDetails.getUsername())));
    }

    @Operation(summary = "Open item dropdown", description = "One customer's (side=RECEIVABLE) or supplier's (side=PAYABLE) OPEN items with their "
            + "balances: direction=CHARGE for what a payment/credit can pay, CREDIT for advances/credit memos to apply or refund. Not cached.")
    @ApiResponse(responseCode = "200", description = "Open item options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/open-items")
    @PreAuthorize("@lookupAccess.can(authentication, 'OPEN_ITEM')")
    public ResponseEntity<AppResponse<PageResponse<OpenItemResponse>>> openItems(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "RECEIVABLE or PAYABLE") @RequestParam AccountSide side,
            @Parameter(description = "Required for RECEIVABLE") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Required for PAYABLE") @RequestParam(required = false) Long supplierId,
            @Parameter(description = "CHARGE or CREDIT; omit for both") @RequestParam(required = false) OpenItemDirection direction,
            @Parameter(description = "Search text (contains) over the reference number") @RequestParam(required = false) @Size(max = 255) String q,
            Pageable pageable) {
        return ok(lookupService.openItems(userDetails.getUsername(), company(companyId, headerCompanyId), side, customerId, supplierId,
                direction, q, pageable));
    }

    @Operation(summary = "Billable delivery receipt dropdown", description = "Plain-customer Delivery Receipts a Sales Invoice can still bill "
            + "(not voided, not billed), newest first, with their lines.")
    @ApiResponse(responseCode = "200", description = "Delivery receipt options returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping("/billable-delivery-receipts")
    @PreAuthorize("@lookupAccess.can(authentication, 'DELIVERY_RECEIPT')")
    public ResponseEntity<AppResponse<PageResponse<TransactionLookupResponse>>> billableDeliveryReceipts(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = COMPANY_DESC) @RequestParam(required = false) Long companyId,
            @Parameter(hidden = true) @RequestHeader(value = COMPANY_HEADER, required = false) Long headerCompanyId,
            @Parameter(description = "Search text (contains) over reference and sheet number") @RequestParam(required = false) @Size(max = 255) String q,
            @Parameter(description = "Filter by customer ID") @RequestParam(required = false) Long customerId,
            Pageable pageable) {
        return ok(lookupService.billableDeliveryReceipts(userDetails.getUsername(), company(companyId, headerCompanyId), q, customerId, pageable));
    }

    private static Long company(Long companyId, Long headerCompanyId) {
        return companyId != null ? companyId : headerCompanyId;
    }

    private static <T> ResponseEntity<AppResponse<PageResponse<T>>> ok(Page<T> page) {
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(page)));
    }
}
