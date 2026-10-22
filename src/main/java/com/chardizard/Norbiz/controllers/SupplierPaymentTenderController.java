package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.IssuedCheckStatus;
import com.chardizard.Norbiz.models.IssuedPaymentMode;
import com.chardizard.Norbiz.services.CheckStatusService;
import com.chardizard.Norbiz.util.DateRangeUtils;
import com.chardizard.Norbiz.util.ListSort;
import com.chardizard.Norbiz.util.ListTotals;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Supplier Payment Tenders", description = "Issued checks/transfers across supplier payments — the outstanding-checks list "
        + "(VIEW_SUPPLIER_PAYMENT) — and the issued-check lifecycle: clear and cancel (UPDATE_ISSUED_CHECK_STATUS). "
        + "See docs/ACCOUNTING.md \"Check lifecycles\".")
@RestController
@RequestMapping("/supplier-payment-tenders")
@RequiredArgsConstructor
public class SupplierPaymentTenderController {

    // Column key → entity path for ?sort= (ListSort); anything else is a 400.
    private static final Map<String, String> SORTABLE = ListSort.fields(
            "paymentReferenceNumber", "supplierPayment.referenceNumber",
            "paymentDate", "supplierPayment.paymentDate",
            "supplier", "supplierPayment.supplier.name",
            "mode", "mode",
            "bankAccount", "bankAccount.name",
            "checkNumber", "checkNumber",
            "checkDate", "checkDate",
            "amount", "amount",
            "status", "status",
            "statusDate", "statusDate");

    private final CheckStatusService checkStatusService;

    @Operation(summary = "List issued tenders", description = "E.g. ?mode=CHECK&status=ISSUED for outstanding checks. Default order: check date, oldest first.")
    @ApiResponse(responseCode = "200", description = "List returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_SUPPLIER_PAYMENT (or a permission that acts on tenders)")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('VIEW_SUPPLIER_PAYMENT', 'UPDATE_ISSUED_CHECK_STATUS')")
    public ResponseEntity<AppResponse<PageResponse<IssuedTenderResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by supplier ID") @RequestParam(required = false) Long supplierId,
            @Parameter(description = "Filter by mode") @RequestParam(required = false) IssuedPaymentMode mode,
            @Parameter(description = "Filter by status") @RequestParam(required = false) IssuedCheckStatus status,
            @Parameter(description = "Filter by our bank account") @RequestParam(required = false) Long bankAccountId,
            @Parameter(description = "Filter by check number (contains)") @RequestParam(required = false) String checkNumber,
            @Parameter(description = "Filter by payment reference (contains)") @RequestParam(required = false) String paymentReferenceNumber,
            @Parameter(description = "Filter by supplier name (contains)") @RequestParam(required = false) String supplier,
            @Parameter(description = "Include tenders of voided payments (default false)") @RequestParam(defaultValue = "false") boolean includeVoided,
            @Parameter(description = "Filter by check date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String checkDateFrom,
            @Parameter(description = "Filter by check date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String checkDateTo,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals,
            Pageable pageable) {
        Pageable sorted = ListSort.apply(pageable, SORTABLE, Sort.by("checkDate", "id"));
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(checkNumber)) filters.put("checkNumber", checkNumber);
        if (StringUtils.hasText(paymentReferenceNumber)) filters.put("paymentReferenceNumber", paymentReferenceNumber);
        if (StringUtils.hasText(supplier)) filters.put("supplier", supplier);
        if (!includeVoided) filters.put("paymentVoided", "false");
        Instant fromInstant = DateRangeUtils.startOfDayUtc(checkDateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(checkDateTo);

        var page = checkStatusService.findIssuedForUser(userDetails.getUsername(), supplierId, mode, status, bankAccountId, filters,
                fromInstant, toInstant, sorted).map(AccountingResponses::issuedTender);
        var response = PageResponse.of(page);
        if (totals) {
            response.withTotals(ListTotals.within(page.getTotalElements(), () -> checkStatusService.findIssuedForUser(userDetails.getUsername(),
                            supplierId, mode, status, bankAccountId, filters, fromInstant, toInstant, ListSort.unpaged(sorted))
                            .map(AccountingResponses::issuedTender).getContent(),
                    ListTotals.column("amount", IssuedTenderResponse::getAmount)));
        }
        return ResponseEntity.ok(AppResponse.of(response));
    }

    @Operation(summary = "Get issued tender by ID")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_SUPPLIER_PAYMENT permission or no access to company")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('VIEW_SUPPLIER_PAYMENT', 'UPDATE_ISSUED_CHECK_STATUS')")
    public ResponseEntity<AppResponse<IssuedTenderResponse>> getById(@Parameter(description = "Tender ID") @PathVariable Long id,
                                                                     @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(AccountingResponses.issuedTender(checkStatusService.findIssued(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Mark checks cleared", description = "ISSUED checks → CLEARED: what they paid stops counting as pending. The bank entry itself "
            + "is cleared by Bank Reconciliation, which also clears the checks automatically.")
    @ApiResponse(responseCode = "200", description = "Checks cleared")
    @ApiResponse(responseCode = "400", description = "A check isn't ISSUED, or the date is before its last status date")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_ISSUED_CHECK_STATUS permission or no access to company")
    @PostMapping("/clear")
    @PreAuthorize("hasAuthority('UPDATE_ISSUED_CHECK_STATUS')")
    public ResponseEntity<AppResponse<List<IssuedTenderResponse>>> clear(@Valid @RequestBody CheckClearRequest request,
                                                                         @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(checkStatusService.clearIssued(request.getTenderIds(), request.getClearedDate(),
                userDetails.getUsername()).stream().map(AccountingResponses::issuedTender).toList()));
    }

    @Operation(summary = "Cancel an issued check", description = "An outstanding (ISSUED) check is stopped, stale or returned unpaid: what it paid is "
            + "reversed so the invoices reopen, its unapplied advance is cancelled, and its disbursement is voided in the bank book (refused once "
            + "reconciled). Replace it with a new Supplier Payment tender whose replacesTenderId is this one.")
    @ApiResponse(responseCode = "200", description = "Check cancelled")
    @ApiResponse(responseCode = "400", description = "Not ISSUED, or its bank entry is reconciled")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_ISSUED_CHECK_STATUS permission or no access to company")
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('UPDATE_ISSUED_CHECK_STATUS')")
    public ResponseEntity<AppResponse<IssuedTenderResponse>> cancel(@Parameter(description = "Tender ID") @PathVariable Long id,
                                                                    @Valid @RequestBody CheckCancelRequest request,
                                                                    @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(AccountingResponses.issuedTender(checkStatusService.cancel(id, request.getCancelledDate(),
                request.getReason(), request.getRemarks(), userDetails.getUsername()))));
    }
}
