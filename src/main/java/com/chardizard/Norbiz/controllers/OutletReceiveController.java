package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.OutletReceiveLineResponse;
import com.chardizard.Norbiz.dto.OutletReceiveRequest;
import com.chardizard.Norbiz.dto.OutletReceiveResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.OutletReceive;
import com.chardizard.Norbiz.models.OutletReceiveLine;
import com.chardizard.Norbiz.services.OutletReceiveService;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "Outlet Receives", description = "Outlet receive transactions — requires VIEW_OUTLET_RECEIVE / CREATE_OUTLET_RECEIVE / VOID_OUTLET_RECEIVE " +
        "permissions. Receives are immutable once posted: only create, view, and void are supported. Each receive is posted against an outlet " +
        "Delivery Receipt's in-transit quantity, deducting Transit Quantity and adding to main Quantity in the outlet's warehouse. Supports partial receiving.")
@RestController
@RequestMapping("/outlet-receives")
@RequiredArgsConstructor
public class OutletReceiveController {

    private final OutletReceiveService outletReceiveService;

    @Operation(summary = "List outlet receives", description = "Returns receives belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Receive list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_RECEIVE permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_OUTLET_RECEIVE')")
    public ResponseEntity<AppResponse<PageResponse<OutletReceiveResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by outlet customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by delivery receipt ID") @RequestParam(required = false) Long deliveryReceiptId,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by receipt date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by receipt date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);

        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var receives = outletReceiveService.findAllForUser(userDetails.getUsername(), customerId, deliveryReceiptId, filters, fromInstant, toInstant, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(receives)));
    }

    @Operation(summary = "Get outlet receive by ID")
    @ApiResponse(responseCode = "200", description = "Receive returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_RECEIVE permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Receive not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_OUTLET_RECEIVE')")
    public ResponseEntity<AppResponse<OutletReceiveResponse>> getById(@Parameter(description = "Outlet receive ID") @PathVariable Long id,
                                                                      @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(outletReceiveService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Post outlet receive", description = "Receives (part of) an outlet Delivery Receipt's in-transit quantity into the outlet " +
            "warehouse's on-hand stock. Each line's quantity must not exceed that item's outstanding amount on the receipt.")
    @ApiResponse(responseCode = "201", description = "Receive posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. non-outlet or voided/fully received delivery receipt, over-receive)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_OUTLET_RECEIVE permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_OUTLET_RECEIVE')")
    public ResponseEntity<AppResponse<OutletReceiveResponse>> create(@Valid @RequestBody OutletReceiveRequest request,
                                                                     @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(outletReceiveService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Void outlet receive", description = "Cancels a receive by reversing its ledger entries (giving the amount back to " +
            "in-transit) and reopening the Delivery Receipt by the voided amounts. Fails if already voided.")
    @ApiResponse(responseCode = "200", description = "Receive voided")
    @ApiResponse(responseCode = "400", description = "Already voided")
    @ApiResponse(responseCode = "403", description = "Missing VOID_OUTLET_RECEIVE permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_OUTLET_RECEIVE')")
    public ResponseEntity<AppResponse<OutletReceiveResponse>> voidOutletReceive(@Parameter(description = "Outlet receive ID") @PathVariable Long id,
                                                                                @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(outletReceiveService.voidOutletReceive(id, userDetails.getUsername()))));
    }

    private OutletReceiveResponse toResponse(OutletReceive receive) {
        OutletReceiveResponse res = new OutletReceiveResponse();
        res.setId(receive.getId());
        res.setCompanyId(receive.getCompany().getId());
        res.setCompanyName(receive.getCompany().getName());
        res.setDeliveryReceiptId(receive.getDeliveryReceipt().getId());
        res.setDeliveryReceiptReferenceNumber(receive.getDeliveryReceipt().getReferenceNumber());
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
        res.setLines(receive.getLines().stream().map(this::toLineResponse).collect(Collectors.toList()));
        return res;
    }

    private OutletReceiveLineResponse toLineResponse(OutletReceiveLine line) {
        OutletReceiveLineResponse res = new OutletReceiveLineResponse();
        res.setId(line.getId());
        res.setItemId(line.getItem().getId());
        res.setItemCode(line.getItem().getItemCode());
        res.setItemName(line.getItem().getName());
        res.setDeliveryReceiptLineId(line.getDeliveryReceiptLine().getId());
        res.setQuantity(line.getQuantity());
        res.setLineNumber(line.getLineNumber());
        res.setQuantityLoaded(line.getQuantityLoaded());
        return res;
    }
}
