package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.CustomerRefund;
import com.chardizard.Norbiz.models.TransactionOrigin;
import com.chardizard.Norbiz.services.RefundService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "Customer Refunds", description = "Pays customers back unused credit (advances, credit memos) — requires VIEW_CUSTOMER_REFUND / "
        + "CREATE_CUSTOMER_REFUND / VOID_CUSTOMER_REFUND. Immutable once posted: only create, view, and void.")
@RestController
@RequestMapping("/customer-refunds")
@RequiredArgsConstructor
public class CustomerRefundController {

    // Column key → entity path for ?sort= (ListSort); anything else is a 400.
    private static final Map<String, String> SORTABLE = ListSort.fields(
            "company", "company.name",
            "referenceNumber", "referenceNumber",
            "sheetNumber", "sheetNumber",
            "voided", "voided",
            "customer", "customer.name",
            "date", "refundDate",
            "mode", "mode",
            "checkNumber", "checkNumber",
            "amount", "amount");

    private final RefundService refundService;

    @Operation(summary = "List customer refunds", description = "Returns refunds belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "List returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_CUSTOMER_REFUND permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_CUSTOMER_REFUND')")
    public ResponseEntity<AppResponse<PageResponse<RefundResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by customer name (contains)") @RequestParam(required = false) String customer,
            @Parameter(description = "Filter by check number (contains)") @RequestParam(required = false) String checkNumber,
            @Parameter(description = "Filter by voided status (true or false)") @RequestParam(required = false) String voided,
            @Parameter(description = "Filter by refund date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by refund date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            @Parameter(description = "Filter by origin") @RequestParam(required = false) TransactionOrigin origin,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals,
            Pageable pageable) {
        Pageable sorted = ListSort.apply(pageable, SORTABLE, ListSort.CREATED_FIRST);
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);
        if (StringUtils.hasText(customer)) filters.put("customer", customer);
        if (StringUtils.hasText(checkNumber)) filters.put("checkNumber", checkNumber);
        if (StringUtils.hasText(voided)) filters.put("voided", voided);
        if (origin != null) filters.put("origin", origin.name());
        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var page = refundService.findCustomerRefunds(userDetails.getUsername(), customerId, filters, fromInstant, toInstant, sorted).map(this::toResponse);
        var response = PageResponse.of(page);
        if (totals) {
            response.withTotals(ListTotals.within(page.getTotalElements(), () -> refundService.findCustomerRefunds(userDetails.getUsername(),
                            customerId, filters, fromInstant, toInstant, ListSort.unpaged(sorted)).map(this::toResponse).getContent(),
                    ListTotals.column("amount", RefundResponse::getAmount)));
        }
        return ResponseEntity.ok(AppResponse.of(response));
    }

    @Operation(summary = "Get customer refund by ID")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_CUSTOMER_REFUND permission or no access to company")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_CUSTOMER_REFUND')")
    public ResponseEntity<AppResponse<RefundResponse>> getById(@Parameter(description = "Refund ID") @PathVariable Long id,
                                                               @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(refundService.findCustomerRefund(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Post customer refund", description = "Each line refunds part of one of the customer's unapplied credits (GET /lookups/open-items "
            + "?direction=CREDIT); a credit paid by a check that hasn't cleared can't be refunded yet. Paid with one instrument: CHECK/BANK_TRANSFER "
            + "need bankAccountId (CHECK also checkNumber/checkDate, ISSUED until reconciled). Numbered CRF-.")
    @ApiResponse(responseCode = "201", description = "Posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. over-refund, credit of another customer, uncleared check)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_CUSTOMER_REFUND permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_CUSTOMER_REFUND')")
    public ResponseEntity<AppResponse<RefundResponse>> create(@Valid @RequestBody CustomerRefundRequest request,
                                                              @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(refundService.createCustomerRefund(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Void customer refund", description = "Reopens the refunded credits and voids the disbursement (refused once reconciled).")
    @ApiResponse(responseCode = "200", description = "Voided")
    @ApiResponse(responseCode = "400", description = "Already voided, or reconciled")
    @ApiResponse(responseCode = "403", description = "Missing VOID_CUSTOMER_REFUND permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_CUSTOMER_REFUND')")
    public ResponseEntity<AppResponse<RefundResponse>> voidRefund(@Parameter(description = "Refund ID") @PathVariable Long id,
                                                                  @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(refundService.voidCustomerRefund(id, userDetails.getUsername()))));
    }

    private RefundResponse toResponse(CustomerRefund refund) {
        RefundResponse res = new RefundResponse();
        res.setId(refund.getId());
        res.setCompanyId(refund.getCompany().getId());
        res.setCompanyName(refund.getCompany().getName());
        res.setCustomerId(refund.getCustomer().getId());
        res.setCustomerName(refund.getCustomer().getName());
        res.setReferenceNumber(refund.getReferenceNumber());
        res.setSheetNumber(refund.getSheetNumber());
        res.setRefundDate(refund.getRefundDate());
        res.setRemarks(refund.getRemarks());
        res.setAmount(refund.getAmount());
        res.setMode(refund.getMode().name());
        if (refund.getBankAccount() != null) {
            res.setBankAccountId(refund.getBankAccount().getId());
            res.setBankAccountName(refund.getBankAccount().getName());
        }
        res.setCheckNumber(refund.getCheckNumber());
        res.setCheckDate(refund.getCheckDate());
        res.setStatus(refund.getStatus().name());
        if (refund.getOpenItem() != null) res.setOpenItemId(refund.getOpenItem().getId());
        res.setCreatedAt(refund.getCreatedAt());
        res.setCreatedBy(refund.getCreatedBy());
        res.setVoided(refund.isVoided());
        res.setVoidedAt(refund.getVoidedAt());
        res.setVoidedBy(refund.getVoidedBy());
        res.setOrigin(refund.getOrigin());
        res.setLines(refund.getLines().stream().map(l -> {
            RefundLineResponse line = new RefundLineResponse();
            line.setId(l.getId());
            line.setCreditItemId(l.getCreditItem().getId());
            line.setCreditSourceType(l.getCreditItem().getSourceType());
            line.setCreditReferenceNumber(l.getCreditItem().getReferenceNumber());
            line.setAmount(l.getAmount());
            line.setLineNumber(l.getLineNumber());
            return line;
        }).toList());
        return res;
    }
}
