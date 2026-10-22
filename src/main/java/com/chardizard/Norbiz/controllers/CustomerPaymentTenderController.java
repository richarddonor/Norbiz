package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.ReceivedCheckStatus;
import com.chardizard.Norbiz.models.ReceivedPaymentMode;
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

@Tag(name = "Customer Payment Tenders", description = "Received cash/checks/transfers across customer payments — the checks-on-hand / post-dated "
        + "checks list (VIEW_CUSTOMER_PAYMENT) — and the received-check lifecycle: clear and bounce (UPDATE_RECEIVED_CHECK_STATUS). "
        + "See docs/ACCOUNTING.md \"Check lifecycles\".")
@RestController
@RequestMapping("/customer-payment-tenders")
@RequiredArgsConstructor
public class CustomerPaymentTenderController {

    // Column key → entity path for ?sort= (ListSort); anything else is a 400.
    private static final Map<String, String> SORTABLE = ListSort.fields(
            "paymentReferenceNumber", "customerPayment.referenceNumber",
            "paymentDate", "customerPayment.paymentDate",
            "customer", "customerPayment.customer.name",
            "mode", "mode",
            "checkNumber", "checkNumber",
            "checkDate", "checkDate",
            "bank", "bank.name",
            "amount", "amount",
            "status", "status",
            "statusDate", "statusDate");

    private final CheckStatusService checkStatusService;

    @Operation(summary = "List received tenders", description = "E.g. ?mode=CHECK&status=ON_HAND for checks to deposit, with checkDateTo for "
            + "the ones already due. Default order: check date, oldest first.")
    @ApiResponse(responseCode = "200", description = "List returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_CUSTOMER_PAYMENT (or a permission that acts on tenders)")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('VIEW_CUSTOMER_PAYMENT', 'CREATE_DEPOSIT', 'UPDATE_RECEIVED_CHECK_STATUS')")
    public ResponseEntity<AppResponse<PageResponse<ReceivedTenderResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by mode") @RequestParam(required = false) ReceivedPaymentMode mode,
            @Parameter(description = "Filter by status") @RequestParam(required = false) ReceivedCheckStatus status,
            @Parameter(description = "Filter by the account it was deposited to / landed in") @RequestParam(required = false) Long depositBankAccountId,
            @Parameter(description = "Filter by check number (contains)") @RequestParam(required = false) String checkNumber,
            @Parameter(description = "Filter by payment reference (contains)") @RequestParam(required = false) String paymentReferenceNumber,
            @Parameter(description = "Filter by customer name (contains)") @RequestParam(required = false) String customer,
            @Parameter(description = "Filter by drawee bank name (contains)") @RequestParam(required = false) String bank,
            @Parameter(description = "Include tenders of voided payments (default false)") @RequestParam(defaultValue = "false") boolean includeVoided,
            @Parameter(description = "Filter by check date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String checkDateFrom,
            @Parameter(description = "Filter by check date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String checkDateTo,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals,
            Pageable pageable) {
        Pageable sorted = ListSort.apply(pageable, SORTABLE, Sort.by("checkDate", "id"));
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(checkNumber)) filters.put("checkNumber", checkNumber);
        if (StringUtils.hasText(paymentReferenceNumber)) filters.put("paymentReferenceNumber", paymentReferenceNumber);
        if (StringUtils.hasText(customer)) filters.put("customer", customer);
        if (StringUtils.hasText(bank)) filters.put("bank", bank);
        if (!includeVoided) filters.put("paymentVoided", "false");
        Instant fromInstant = DateRangeUtils.startOfDayUtc(checkDateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(checkDateTo);

        var page = checkStatusService.findReceivedForUser(userDetails.getUsername(), customerId, mode, status, depositBankAccountId, filters,
                fromInstant, toInstant, sorted).map(AccountingResponses::receivedTender);
        var response = PageResponse.of(page);
        if (totals) {
            response.withTotals(ListTotals.within(page.getTotalElements(), () -> checkStatusService.findReceivedForUser(userDetails.getUsername(),
                            customerId, mode, status, depositBankAccountId, filters, fromInstant, toInstant, ListSort.unpaged(sorted))
                            .map(AccountingResponses::receivedTender).getContent(),
                    ListTotals.column("amount", ReceivedTenderResponse::getAmount)));
        }
        return ResponseEntity.ok(AppResponse.of(response));
    }

    @Operation(summary = "Get received tender by ID")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_CUSTOMER_PAYMENT permission or no access to company")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('VIEW_CUSTOMER_PAYMENT', 'CREATE_DEPOSIT', 'UPDATE_RECEIVED_CHECK_STATUS')")
    public ResponseEntity<AppResponse<ReceivedTenderResponse>> getById(@Parameter(description = "Tender ID") @PathVariable Long id,
                                                                       @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(AccountingResponses.receivedTender(checkStatusService.findReceived(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Mark checks cleared", description = "DEPOSITED checks → CLEARED: what they paid stops counting as pending (PDC). The bank "
            + "entry itself is cleared by Bank Reconciliation, which also clears its deposits' checks automatically.")
    @ApiResponse(responseCode = "200", description = "Checks cleared")
    @ApiResponse(responseCode = "400", description = "A check isn't DEPOSITED, or the date is before its last status date")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_RECEIVED_CHECK_STATUS permission or no access to company")
    @PostMapping("/clear")
    @PreAuthorize("hasAuthority('UPDATE_RECEIVED_CHECK_STATUS')")
    public ResponseEntity<AppResponse<List<ReceivedTenderResponse>>> clear(@Valid @RequestBody CheckClearRequest request,
                                                                           @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(checkStatusService.clearReceived(request.getTenderIds(), request.getClearedDate(),
                userDetails.getUsername()).stream().map(AccountingResponses::receivedTender).toList()));
    }

    @Operation(summary = "Bounce a check", description = "An ON_HAND or DEPOSITED check bounced (DAIF, DAUD, stop payment...): every application it "
            + "funded is reversed so the invoices reopen (their bouncedAmount grows), its unapplied advance is cancelled, and a deposited check is "
            + "booked as returned against the deposit account. Replace it with a new Customer Payment tender whose replacesTenderId is this one.")
    @ApiResponse(responseCode = "200", description = "Check bounced")
    @ApiResponse(responseCode = "400", description = "Not a check, or not ON_HAND/DEPOSITED")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_RECEIVED_CHECK_STATUS permission or no access to company")
    @PostMapping("/{id}/bounce")
    @PreAuthorize("hasAuthority('UPDATE_RECEIVED_CHECK_STATUS')")
    public ResponseEntity<AppResponse<ReceivedTenderResponse>> bounce(@Parameter(description = "Tender ID") @PathVariable Long id,
                                                                      @Valid @RequestBody CheckBounceRequest request,
                                                                      @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(AccountingResponses.receivedTender(checkStatusService.bounce(id, request.getBouncedDate(),
                request.getReason(), request.getRemarks(), userDetails.getUsername()))));
    }
}
