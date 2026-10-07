package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.models.TransactionOrigin;
import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.DeliveryReceiptLineResponse;
import com.chardizard.Norbiz.dto.DeliveryReceiptRequest;
import com.chardizard.Norbiz.dto.DeliveryReceiptResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.DeliveryReceipt;
import com.chardizard.Norbiz.models.DeliveryReceiptLine;
import com.chardizard.Norbiz.services.DeliveryReceiptService;
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

@Tag(name = "Delivery Receipts", description = "Delivery receipt transactions — requires VIEW_DELIVERY_RECEIPT / CREATE_DELIVERY_RECEIPT / VOID_DELIVERY_RECEIPT " +
        "permissions. Receipts are immutable once posted: only create, view, and void are supported. Stock always leaves the company's main warehouse; " +
        "for an OUTLET customer the same quantity is posted as in-transit in the outlet's own warehouse, to be received by an Outlet Receive.")
@RestController
@RequestMapping("/delivery-receipts")
@RequiredArgsConstructor
public class DeliveryReceiptController {

    private final DeliveryReceiptService deliveryReceiptService;

    @Operation(summary = "List delivery receipts", description = "Returns receipts belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Receipt list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_DELIVERY_RECEIPT permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_DELIVERY_RECEIPT')")
    public ResponseEntity<AppResponse<PageResponse<DeliveryReceiptResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by source (main) warehouse ID") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by delivery date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by delivery date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            @Parameter(description = "Filter by origin: NATIVE, MIGRATED (copied from legacy) or RECONSTRUCTED (created by the migration)") @RequestParam(required = false) TransactionOrigin origin,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);
        if (origin != null) filters.put("origin", origin.name());

        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var receipts = deliveryReceiptService.findAllForUser(userDetails.getUsername(), customerId, warehouseId, filters, fromInstant, toInstant, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(receipts)));
    }

    @Operation(summary = "Get delivery receipt by ID")
    @ApiResponse(responseCode = "200", description = "Receipt returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_DELIVERY_RECEIPT permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Receipt not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_DELIVERY_RECEIPT')")
    public ResponseEntity<AppResponse<DeliveryReceiptResponse>> getById(@Parameter(description = "Delivery receipt ID") @PathVariable Long id,
                                                                        @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(deliveryReceiptService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Post delivery receipt", description = "Delivers items to a customer: each line must reference an active INVENTORY item and a quantity > 0; " +
            "unitPrice defaults to the item's UNIT_PRICE when omitted. Deducts on-hand stock from the company's main warehouse; for an OUTLET customer " +
            "also adds the quantity to the outlet warehouse's in-transit stock.")
    @ApiResponse(responseCode = "201", description = "Receipt posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. no main warehouse, inactive customer/item, mismatched company)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_DELIVERY_RECEIPT permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_DELIVERY_RECEIPT')")
    public ResponseEntity<AppResponse<DeliveryReceiptResponse>> create(@Valid @RequestBody DeliveryReceiptRequest request,
                                                                       @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(deliveryReceiptService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Void delivery receipt", description = "Cancels a receipt by reversing every ledger entry it posted. " +
            "Fails if already voided or if an Outlet Receive has received any of it (void those first).")
    @ApiResponse(responseCode = "200", description = "Receipt voided")
    @ApiResponse(responseCode = "400", description = "Already voided, or already received by an outlet")
    @ApiResponse(responseCode = "403", description = "Missing VOID_DELIVERY_RECEIPT permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_DELIVERY_RECEIPT')")
    public ResponseEntity<AppResponse<DeliveryReceiptResponse>> voidDeliveryReceipt(@Parameter(description = "Delivery receipt ID") @PathVariable Long id,
                                                                                    @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(deliveryReceiptService.voidDeliveryReceipt(id, userDetails.getUsername()))));
    }

    private DeliveryReceiptResponse toResponse(DeliveryReceipt receipt) {
        DeliveryReceiptResponse res = new DeliveryReceiptResponse();
        res.setId(receipt.getId());
        res.setCompanyId(receipt.getCompany().getId());
        res.setCompanyName(receipt.getCompany().getName());
        res.setCustomerId(receipt.getCustomer().getId());
        res.setCustomerName(receipt.getCustomer().getName());
        res.setCustomerType(receipt.getCustomer().getType());
        res.setWarehouseId(receipt.getWarehouse().getId());
        res.setWarehouseName(receipt.getWarehouse().getName());
        if (receipt.getDestinationWarehouse() != null) {
            res.setDestinationWarehouseId(receipt.getDestinationWarehouse().getId());
            res.setDestinationWarehouseName(receipt.getDestinationWarehouse().getName());
        }
        if (receipt.getStockTransfer() != null) {
            res.setStockTransferId(receipt.getStockTransfer().getId());
            res.setStockTransferReferenceNumber(receipt.getStockTransfer().getReferenceNumber());
        }
        res.setReferenceNumber(receipt.getReferenceNumber());
        res.setSheetNumber(receipt.getSheetNumber());
        res.setDeliveryDate(receipt.getDeliveryDate());
        res.setRemarks(receipt.getRemarks());
        res.setCreatedAt(receipt.getCreatedAt());
        res.setCreatedBy(receipt.getCreatedBy());
        res.setVoided(receipt.isVoided());
        res.setVoidedAt(receipt.getVoidedAt());
        res.setVoidedBy(receipt.getVoidedBy());
        res.setLoaded(receipt.isLoaded());
        res.setOrigin(receipt.getOrigin());
        List<DeliveryReceiptLineResponse> lines = receipt.getLines().stream().map(this::toLineResponse).toList();
        res.setLines(lines);
        res.setTotalAmount(lines.stream().map(DeliveryReceiptLineResponse::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        return res;
    }

    private DeliveryReceiptLineResponse toLineResponse(DeliveryReceiptLine line) {
        DeliveryReceiptLineResponse res = new DeliveryReceiptLineResponse();
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
