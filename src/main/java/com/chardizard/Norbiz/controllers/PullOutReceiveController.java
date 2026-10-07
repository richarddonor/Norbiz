package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.services.PullOutReceiveService;
import com.chardizard.Norbiz.util.DateRangeUtils;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Pull Out Receives", description = "Pull out receive transactions — requires VIEW_PULL_OUT_RECEIVE / CREATE_PULL_OUT_RECEIVE / VOID_PULL_OUT_RECEIVE permissions. " +
        "Receives an Outlet Pull Out's in-transit quantity into the main warehouse's on-hand stock. Supports partial receiving. " +
        "Immutable once posted: only create, view, and void.")
@RestController
@RequestMapping("/pull-out-receives")
@RequiredArgsConstructor
public class PullOutReceiveController {

    private final PullOutReceiveService pullOutReceiveService;

    @Operation(summary = "List pull out receives", description = "Returns pull out receives belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "List returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PULL_OUT_RECEIVE permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_PULL_OUT_RECEIVE')")
    public ResponseEntity<AppResponse<PageResponse<PullOutReceiveResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by outlet pull out ID") @RequestParam(required = false) Long outletPullOutId,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by receipt date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by receipt date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            @Parameter(description = "Filter by origin: NATIVE, MIGRATED (copied from legacy) or RECONSTRUCTED (created by the migration)") @RequestParam(required = false) TransactionOrigin origin,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);
        if (origin != null) filters.put("origin", origin.name());

        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var page = pullOutReceiveService.findAllForUser(userDetails.getUsername(), customerId, outletPullOutId, filters, fromInstant, toInstant, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(page)));
    }

    @Operation(summary = "Get Pull out receive by ID")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PULL_OUT_RECEIVE permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_PULL_OUT_RECEIVE')")
    public ResponseEntity<AppResponse<PullOutReceiveResponse>> getById(@Parameter(description = "Pull out receive ID") @PathVariable Long id,
                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(pullOutReceiveService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Post Pull out receive", description = "Receives (part of) an Outlet Pull Out's in-transit quantity into the main warehouse. Each line's quantity must not " +
            "exceed that item's outstanding amount on the pull out.")
    @ApiResponse(responseCode = "201", description = "Posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. voided/fully received pull out, over-receive)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_PULL_OUT_RECEIVE permission or no access to company")
    @ApiResponse(responseCode = "409", description = "Insufficient stock (code INSUFFICIENT_STOCK)")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_PULL_OUT_RECEIVE')")
    public ResponseEntity<AppResponse<PullOutReceiveResponse>> create(@Valid @RequestBody PullOutReceiveRequest request,
                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(pullOutReceiveService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Void Pull out receive", description = "Cancels a receive by reversing its ledger entries (giving the amount back to in-transit) and reopening the Outlet Pull Out " +
            "by the voided amounts. Fails if already voided, or if the received stock has since left the main warehouse.")
    @ApiResponse(responseCode = "200", description = "Voided")
    @ApiResponse(responseCode = "400", description = "Already voided")
    @ApiResponse(responseCode = "403", description = "Missing VOID_PULL_OUT_RECEIVE permission or no access to company")
    @ApiResponse(responseCode = "409", description = "Insufficient stock (code INSUFFICIENT_STOCK)")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_PULL_OUT_RECEIVE')")
    public ResponseEntity<AppResponse<PullOutReceiveResponse>> voidPullOutReceive(@Parameter(description = "Pull out receive ID") @PathVariable Long id,
                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(pullOutReceiveService.voidPullOutReceive(id, userDetails.getUsername()))));
    }

    private PullOutReceiveResponse toResponse(PullOutReceive receive) {
        PullOutReceiveResponse res = new PullOutReceiveResponse();
        res.setId(receive.getId());
        res.setCompanyId(receive.getCompany().getId());
        res.setCompanyName(receive.getCompany().getName());
        res.setOutletPullOutId(receive.getOutletPullOut().getId());
        res.setOutletPullOutReferenceNumber(receive.getOutletPullOut().getReferenceNumber());
        res.setCustomerId(receive.getCustomer().getId());
        res.setCustomerName(receive.getCustomer().getName());
        res.setWarehouseId(receive.getWarehouse().getId());
        res.setWarehouseName(receive.getWarehouse().getName());
        res.setReferenceNumber(receive.getReferenceNumber());
        res.setSheetNumber(receive.getSheetNumber());
        res.setReceiptDate(receive.getReceiptDate());
        res.setRemarks(receive.getRemarks());
        res.setCreatedAt(receive.getCreatedAt());
        res.setCreatedBy(receive.getCreatedBy());
        res.setVoided(receive.isVoided());
        res.setVoidedAt(receive.getVoidedAt());
        res.setVoidedBy(receive.getVoidedBy());
        res.setLoaded(receive.isLoaded());
        res.setOrigin(receive.getOrigin());
        res.setLines(receive.getLines().stream().map(this::toLineResponse).toList());
        return res;
    }

    private PullOutReceiveLineResponse toLineResponse(PullOutReceiveLine line) {
        PullOutReceiveLineResponse res = new PullOutReceiveLineResponse();
        res.setId(line.getId());
        res.setItemId(line.getItem().getId());
        res.setItemCode(line.getItem().getItemCode());
        res.setItemName(line.getItem().getName());
        res.setOutletPullOutLineId(line.getOutletPullOutLine().getId());
        res.setQuantity(line.getQuantity());
        res.setLineNumber(line.getLineNumber());
        res.setQuantityLoaded(line.getQuantityLoaded());
        return res;
    }
}
