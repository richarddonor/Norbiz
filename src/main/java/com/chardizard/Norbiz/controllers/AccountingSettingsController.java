package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AccountingSettingsRequest;
import com.chardizard.Norbiz.dto.AccountingSettingsResponse;
import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.models.AccountingSettings;
import com.chardizard.Norbiz.services.AccountingSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Accounting Settings", description = "Per-company AR/AP settings: the accounting start date (go-live cut-off) and check clearing days. "
        + "Reading needs any AR/AP view permission or MANAGE_ACCOUNTING_SETTINGS; saving needs MANAGE_ACCOUNTING_SETTINGS.")
@RestController
@RequestMapping("/accounting/settings")
@RequiredArgsConstructor
public class AccountingSettingsController {

    private final AccountingSettingsService settingsService;

    @Operation(summary = "Get accounting settings", description = "configured=false until the start date has been set.")
    @ApiResponse(responseCode = "200", description = "Settings returned")
    @ApiResponse(responseCode = "403", description = "No qualifying permission or no access to company")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('MANAGE_ACCOUNTING_SETTINGS', 'VIEW_SALES_INVOICE', 'VIEW_CUSTOMER_PAYMENT', 'VIEW_SUPPLIER_PAYMENT', "
            + "'VIEW_CUSTOMER_MEMO', 'VIEW_SUPPLIER_MEMO', 'VIEW_AR_REPORT', 'VIEW_AP_REPORT')")
    public ResponseEntity<AppResponse<AccountingSettingsResponse>> get(@Parameter(description = "Company ID") @RequestParam Long companyId,
                                                                       @AuthenticationPrincipal UserDetails userDetails) {
        AccountingSettingsResponse res = settingsService.find(companyId, userDetails.getUsername())
                .map(this::toResponse)
                .orElseGet(() -> {
                    AccountingSettingsResponse empty = new AccountingSettingsResponse();
                    empty.setCompanyId(companyId);
                    empty.setCheckClearingDays(3);
                    return empty;
                });
        return ResponseEntity.ok(AppResponse.of(res));
    }

    @Operation(summary = "Save accounting settings", description = "Sets the accounting start date: Sales/Purchase Invoices dated on/after it are tracked "
            + "as open items; earlier balances are entered as OPENING_BALANCE memos. Setting or moving it (re)opens the payables of Purchase "
            + "Invoices posted through Norbiz on/after it. It can't move once anything has been applied or any other AR/AP document posted.")
    @ApiResponse(responseCode = "200", description = "Settings saved")
    @ApiResponse(responseCode = "400", description = "Validation error, or the start date can no longer change")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE_ACCOUNTING_SETTINGS permission or no access to company")
    @PutMapping
    @PreAuthorize("hasAuthority('MANAGE_ACCOUNTING_SETTINGS')")
    public ResponseEntity<AppResponse<AccountingSettingsResponse>> save(@Valid @RequestBody AccountingSettingsRequest request,
                                                                        @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(settingsService.save(request, userDetails.getUsername()))));
    }

    private AccountingSettingsResponse toResponse(AccountingSettings settings) {
        AccountingSettingsResponse res = new AccountingSettingsResponse();
        res.setCompanyId(settings.getCompany().getId());
        res.setConfigured(true);
        res.setStartDate(settings.getStartDate());
        res.setCheckClearingDays(settings.getCheckClearingDays());
        res.setUpdatedAt(settings.getUpdatedAt());
        res.setUpdatedBy(settings.getUpdatedBy());
        return res;
    }
}
