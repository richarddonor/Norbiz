package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.services.OutletPullOutService;
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

@Tag(name = "Outlet Pull Outs", description = "Outlet pull out transactions — requires VIEW_OUTLET_PULL_OUT / CREATE_OUTLET_PULL_OUT / VOID_OUTLET_PULL_OUT permissions. " +
        "Pulls stock out of an outlet: deducts on-hand in the outlet's warehouse and posts it in transit in the main warehouse, " +
        "to be received by Pull Out Receive. Immutable once posted: only create, view, and void.")
@RestController
@RequestMapping("/outlet-pull-outs")
@RequiredArgsConstructor
public class OutletPullOutController {

    private final OutletPullOutService outletPullOutService;

    @Operation(summary = "List outlet pull outs", description = "Returns outlet pull outs belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "List returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_PULL_OUT permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_OUTLET_PULL_OUT')")
    public ResponseEntity<AppResponse<PageResponse<OutletPullOutResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by pull out reason ID") @RequestParam(required = false) Long pullOutReasonId,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by pull out date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by pull out date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            @Parameter(description = "Filter by origin: NATIVE, MIGRATED (copied from legacy) or RECONSTRUCTED (created by the migration)") @RequestParam(required = false) TransactionOrigin origin,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);
        if (origin != null) filters.put("origin", origin.name());

        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var page = outletPullOutService.findAllForUser(userDetails.getUsername(), customerId, pullOutReasonId, filters, fromInstant, toInstant, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(page)));
    }

    @Operation(summary = "Get Outlet pull out by ID")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_PULL_OUT permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_OUTLET_PULL_OUT')")
    public ResponseEntity<AppResponse<OutletPullOutResponse>> getById(@Parameter(description = "Outlet pull out ID") @PathVariable Long id,
                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(outletPullOutService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Post Outlet pull out", description = "Pulls stock out of an outlet: each line must reference an active INVENTORY item with a quantity > 0 that is on hand " +
            "in the outlet's warehouse. A pull out reason is required. Unit price defaults to the item's UNIT_PRICE.")
    @ApiResponse(responseCode = "201", description = "Posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. non-outlet or inactive customer, inactive reason/item, no main warehouse)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_OUTLET_PULL_OUT permission or no access to company")
    @ApiResponse(responseCode = "409", description = "Insufficient stock (code INSUFFICIENT_STOCK)")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_OUTLET_PULL_OUT')")
    public ResponseEntity<AppResponse<OutletPullOutResponse>> create(@Valid @RequestBody OutletPullOutRequest request,
                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(outletPullOutService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Void Outlet pull out", description = "Cancels a pull out by reversing both ledger entries. Fails if already voided or if a Pull Out Receive has received any of it (void those first).")
    @ApiResponse(responseCode = "200", description = "Voided")
    @ApiResponse(responseCode = "400", description = "Already voided or already received")
    @ApiResponse(responseCode = "403", description = "Missing VOID_OUTLET_PULL_OUT permission or no access to company")
    @ApiResponse(responseCode = "409", description = "Insufficient stock (code INSUFFICIENT_STOCK)")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_OUTLET_PULL_OUT')")
    public ResponseEntity<AppResponse<OutletPullOutResponse>> voidOutletPullOut(@Parameter(description = "Outlet pull out ID") @PathVariable Long id,
                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(outletPullOutService.voidOutletPullOut(id, userDetails.getUsername()))));
    }

    private OutletPullOutResponse toResponse(OutletPullOut pullOut) {
        OutletPullOutResponse res = new OutletPullOutResponse();
        res.setId(pullOut.getId());
        res.setCompanyId(pullOut.getCompany().getId());
        res.setCompanyName(pullOut.getCompany().getName());
        res.setCustomerId(pullOut.getCustomer().getId());
        res.setCustomerName(pullOut.getCustomer().getName());
        res.setWarehouseId(pullOut.getWarehouse().getId());
        res.setWarehouseName(pullOut.getWarehouse().getName());
        res.setDestinationWarehouseId(pullOut.getDestinationWarehouse().getId());
        res.setDestinationWarehouseName(pullOut.getDestinationWarehouse().getName());
        if (pullOut.getReason() != null) {
            res.setPullOutReasonId(pullOut.getReason().getId());
            res.setPullOutReasonName(pullOut.getReason().getName());
        }
        res.setReferenceNumber(pullOut.getReferenceNumber());
        res.setSheetNumber(pullOut.getSheetNumber());
        res.setPullOutDate(pullOut.getPullOutDate());
        res.setRemarks(pullOut.getRemarks());
        res.setCreatedAt(pullOut.getCreatedAt());
        res.setCreatedBy(pullOut.getCreatedBy());
        res.setVoided(pullOut.isVoided());
        res.setVoidedAt(pullOut.getVoidedAt());
        res.setVoidedBy(pullOut.getVoidedBy());
        res.setLoaded(pullOut.isLoaded());
        res.setOrigin(pullOut.getOrigin());
        List<OutletPullOutLineResponse> lines = pullOut.getLines().stream().map(this::toLineResponse).toList();
        res.setLines(lines);
        res.setTotalAmount(lines.stream().map(OutletPullOutLineResponse::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        return res;
    }

    private OutletPullOutLineResponse toLineResponse(OutletPullOutLine line) {
        OutletPullOutLineResponse res = new OutletPullOutLineResponse();
        res.setId(line.getId());
        res.setItemId(line.getItem().getId());
        res.setItemCode(line.getItem().getItemCode());
        res.setItemName(line.getItem().getName());
        res.setQuantity(line.getQuantity());
        res.setUnitPrice(line.getUnitPrice());
        res.setAmount(line.getQuantity().multiply(line.getUnitPrice()));
        res.setLineNumber(line.getLineNumber());
        res.setQuantityLoaded(line.getQuantityLoaded());
        return res;
    }
}
