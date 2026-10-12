package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.services.PullOutReceiptService;
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

@Tag(name = "Pull-out Receipts", description = "Pull-out receipt transactions — requires VIEW_PULL_OUT_RECEIPT / CREATE_PULL_OUT_RECEIPT / VOID_PULL_OUT_RECEIPT permissions. " +
        "Receives an Outlet Pull-out's in-transit quantity into the main warehouse's on-hand stock. Supports partial receiving. " +
        "Immutable once posted: only create, view, and void.")
@RestController
@RequestMapping("/pull-out-receipts")
@RequiredArgsConstructor
public class PullOutReceiptController {

    private final PullOutReceiptService pullOutReceiptService;

    @Operation(summary = "List pull-out receipts", description = "Returns pull-out receipts belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "List returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PULL_OUT_RECEIPT permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_PULL_OUT_RECEIPT')")
    public ResponseEntity<AppResponse<PageResponse<PullOutReceiptResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by outlet pull-out ID") @RequestParam(required = false) Long outletPullOutId,
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

        var page = pullOutReceiptService.findAllForUser(userDetails.getUsername(), customerId, outletPullOutId, filters, fromInstant, toInstant, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(page)));
    }

    @Operation(summary = "Get Pull-out receipt by ID")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PULL_OUT_RECEIPT permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_PULL_OUT_RECEIPT')")
    public ResponseEntity<AppResponse<PullOutReceiptResponse>> getById(@Parameter(description = "Pull-out receipt ID") @PathVariable Long id,
                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(pullOutReceiptService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Post Pull-out receipt", description = "Receives (part of) an Outlet Pull-out's in-transit quantity into the main warehouse. Each line's quantity must not " +
            "exceed that item's outstanding amount on the pull-out.")
    @ApiResponse(responseCode = "201", description = "Posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. voided/fully received pull-out, over-receiving)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_PULL_OUT_RECEIPT permission or no access to company")
    @ApiResponse(responseCode = "409", description = "Insufficient stock (code INSUFFICIENT_STOCK)")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_PULL_OUT_RECEIPT')")
    public ResponseEntity<AppResponse<PullOutReceiptResponse>> create(@Valid @RequestBody PullOutReceiptRequest request,
                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(pullOutReceiptService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Void Pull-out receipt", description = "Cancels a receipt by reversing its ledger entries (giving the amount back to in-transit) and reopening the Outlet Pull-out " +
            "by the voided amounts. Fails if already voided, or if the received stock has since left the main warehouse.")
    @ApiResponse(responseCode = "200", description = "Voided")
    @ApiResponse(responseCode = "400", description = "Already voided")
    @ApiResponse(responseCode = "403", description = "Missing VOID_PULL_OUT_RECEIPT permission or no access to company")
    @ApiResponse(responseCode = "409", description = "Insufficient stock (code INSUFFICIENT_STOCK)")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_PULL_OUT_RECEIPT')")
    public ResponseEntity<AppResponse<PullOutReceiptResponse>> voidPullOutReceipt(@Parameter(description = "Pull-out receipt ID") @PathVariable Long id,
                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(pullOutReceiptService.voidPullOutReceipt(id, userDetails.getUsername()))));
    }

    private PullOutReceiptResponse toResponse(PullOutReceipt receipt) {
        PullOutReceiptResponse res = new PullOutReceiptResponse();
        res.setId(receipt.getId());
        res.setCompanyId(receipt.getCompany().getId());
        res.setCompanyName(receipt.getCompany().getName());
        res.setOutletPullOutId(receipt.getOutletPullOut().getId());
        res.setOutletPullOutReferenceNumber(receipt.getOutletPullOut().getReferenceNumber());
        res.setCustomerId(receipt.getCustomer().getId());
        res.setCustomerName(receipt.getCustomer().getName());
        res.setWarehouseId(receipt.getWarehouse().getId());
        res.setWarehouseName(receipt.getWarehouse().getName());
        res.setReferenceNumber(receipt.getReferenceNumber());
        res.setSheetNumber(receipt.getSheetNumber());
        res.setReceiptDate(receipt.getReceiptDate());
        res.setRemarks(receipt.getRemarks());
        res.setCreatedAt(receipt.getCreatedAt());
        res.setCreatedBy(receipt.getCreatedBy());
        res.setVoided(receipt.isVoided());
        res.setVoidedAt(receipt.getVoidedAt());
        res.setVoidedBy(receipt.getVoidedBy());
        res.setLoaded(receipt.isLoaded());
        res.setOrigin(receipt.getOrigin());
        res.setLines(receipt.getLines().stream().map(this::toLineResponse).toList());
        return res;
    }

    private PullOutReceiptLineResponse toLineResponse(PullOutReceiptLine line) {
        PullOutReceiptLineResponse res = new PullOutReceiptLineResponse();
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
