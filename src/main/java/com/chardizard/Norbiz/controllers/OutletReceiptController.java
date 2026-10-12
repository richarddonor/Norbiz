package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.models.TransactionOrigin;
import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.OutletReceiptLineResponse;
import com.chardizard.Norbiz.dto.OutletReceiptRequest;
import com.chardizard.Norbiz.dto.OutletReceiptResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.OutletReceipt;
import com.chardizard.Norbiz.models.OutletReceiptLine;
import com.chardizard.Norbiz.services.OutletReceiptService;
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

@Tag(name = "Outlet Receipts", description = "Outlet receipt transactions — requires VIEW_OUTLET_RECEIPT / CREATE_OUTLET_RECEIPT / VOID_OUTLET_RECEIPT " +
        "permissions. Receipts are immutable once posted: only create, view, and void are supported. Each receipt is posted against an outlet " +
        "Delivery Receipt's in-transit quantity, deducting Transit Quantity and adding to main Quantity in the outlet's warehouse. Supports partial receiving.")
@RestController
@RequestMapping("/outlet-receipts")
@RequiredArgsConstructor
public class OutletReceiptController {

    private final OutletReceiptService outletReceiptService;

    @Operation(summary = "List outlet receipts", description = "Returns receipts belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Receipt list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_RECEIPT permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_OUTLET_RECEIPT')")
    public ResponseEntity<AppResponse<PageResponse<OutletReceiptResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by outlet customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by delivery receipt ID") @RequestParam(required = false) Long deliveryReceiptId,
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

        var receipts = outletReceiptService.findAllForUser(userDetails.getUsername(), customerId, deliveryReceiptId, filters, fromInstant, toInstant, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(receipts)));
    }

    @Operation(summary = "Get outlet receipt by ID")
    @ApiResponse(responseCode = "200", description = "Receipt returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_RECEIPT permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Receipt not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_OUTLET_RECEIPT')")
    public ResponseEntity<AppResponse<OutletReceiptResponse>> getById(@Parameter(description = "Outlet receipt ID") @PathVariable Long id,
                                                                      @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(outletReceiptService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Post outlet receipt", description = "Receives (part of) an outlet Delivery Receipt's in-transit quantity into the outlet " +
            "warehouse's on-hand stock. Each line's quantity must not exceed that item's outstanding amount on the delivery receipt.")
    @ApiResponse(responseCode = "201", description = "Receipt posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. non-outlet or voided/fully received delivery receipt, over-receiving)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_OUTLET_RECEIPT permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_OUTLET_RECEIPT')")
    public ResponseEntity<AppResponse<OutletReceiptResponse>> create(@Valid @RequestBody OutletReceiptRequest request,
                                                                     @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(outletReceiptService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Void outlet receipt", description = "Cancels a receipt by reversing its ledger entries (giving the amount back to " +
            "in-transit) and reopening the Delivery Receipt by the voided amounts. Fails if already voided.")
    @ApiResponse(responseCode = "200", description = "Receipt voided")
    @ApiResponse(responseCode = "400", description = "Already voided")
    @ApiResponse(responseCode = "403", description = "Missing VOID_OUTLET_RECEIPT permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_OUTLET_RECEIPT')")
    public ResponseEntity<AppResponse<OutletReceiptResponse>> voidOutletReceipt(@Parameter(description = "Outlet receipt ID") @PathVariable Long id,
                                                                                @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(outletReceiptService.voidOutletReceipt(id, userDetails.getUsername()))));
    }

    private OutletReceiptResponse toResponse(OutletReceipt receipt) {
        OutletReceiptResponse res = new OutletReceiptResponse();
        res.setId(receipt.getId());
        res.setCompanyId(receipt.getCompany().getId());
        res.setCompanyName(receipt.getCompany().getName());
        res.setDeliveryReceiptId(receipt.getDeliveryReceipt().getId());
        res.setDeliveryReceiptReferenceNumber(receipt.getDeliveryReceipt().getReferenceNumber());
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
        res.setLines(receipt.getLines().stream().map(this::toLineResponse).collect(Collectors.toList()));
        return res;
    }

    private OutletReceiptLineResponse toLineResponse(OutletReceiptLine line) {
        OutletReceiptLineResponse res = new OutletReceiptLineResponse();
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
