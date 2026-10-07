package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.services.StockTransferService;
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

@Tag(name = "Stock Transfers", description = "Stock transfer transactions — requires VIEW_STOCK_TRANSFER / CREATE_STOCK_TRANSFER / VOID_STOCK_TRANSFER permissions. " +
        "Sets main-warehouse stock aside for a customer ahead of delivery (a negative transit posting in the main warehouse); " +
        "a Delivery Receipt created from the transfer releases it. Immutable once posted: only create, view, and void.")
@RestController
@RequestMapping("/stock-transfers")
@RequiredArgsConstructor
public class StockTransferController {

    private final StockTransferService stockTransferService;

    @Operation(summary = "List stock transfers", description = "Returns stock transfers belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "List returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_STOCK_TRANSFER permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_STOCK_TRANSFER')")
    public ResponseEntity<AppResponse<PageResponse<StockTransferResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by transfer date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by transfer date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            @Parameter(description = "Filter by origin: NATIVE, MIGRATED (copied from legacy) or RECONSTRUCTED (created by the migration)") @RequestParam(required = false) TransactionOrigin origin,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);
        if (origin != null) filters.put("origin", origin.name());

        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var page = stockTransferService.findAllForUser(userDetails.getUsername(), customerId, filters, fromInstant, toInstant, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(page)));
    }

    @Operation(summary = "Get Stock transfer by ID")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_STOCK_TRANSFER permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_STOCK_TRANSFER')")
    public ResponseEntity<AppResponse<StockTransferResponse>> getById(@Parameter(description = "Stock transfer ID") @PathVariable Long id,
                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(stockTransferService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Post Stock transfer", description = "Sets stock aside in the main warehouse for a customer: each line must reference an active INVENTORY item with a quantity > 0 " +
            "that is on hand in the main warehouse. Unit price defaults to the item's UNIT_PRICE.")
    @ApiResponse(responseCode = "201", description = "Posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. inactive customer/item, no main warehouse)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_STOCK_TRANSFER permission or no access to company")
    @ApiResponse(responseCode = "409", description = "Insufficient stock (code INSUFFICIENT_STOCK)")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_STOCK_TRANSFER')")
    public ResponseEntity<AppResponse<StockTransferResponse>> create(@Valid @RequestBody StockTransferRequest request,
                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(stockTransferService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Void Stock transfer", description = "Cancels a transfer by reversing its transit hold. Fails if already voided or already delivered by a Delivery Receipt (void that first).")
    @ApiResponse(responseCode = "200", description = "Voided")
    @ApiResponse(responseCode = "400", description = "Already voided or already delivered")
    @ApiResponse(responseCode = "403", description = "Missing VOID_STOCK_TRANSFER permission or no access to company")
    @ApiResponse(responseCode = "409", description = "Insufficient stock (code INSUFFICIENT_STOCK)")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_STOCK_TRANSFER')")
    public ResponseEntity<AppResponse<StockTransferResponse>> voidStockTransfer(@Parameter(description = "Stock transfer ID") @PathVariable Long id,
                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(stockTransferService.voidStockTransfer(id, userDetails.getUsername()))));
    }

    private StockTransferResponse toResponse(StockTransfer transfer) {
        StockTransferResponse res = new StockTransferResponse();
        res.setId(transfer.getId());
        res.setCompanyId(transfer.getCompany().getId());
        res.setCompanyName(transfer.getCompany().getName());
        res.setCustomerId(transfer.getCustomer().getId());
        res.setCustomerName(transfer.getCustomer().getName());
        res.setCustomerType(transfer.getCustomer().getType());
        res.setWarehouseId(transfer.getWarehouse().getId());
        res.setWarehouseName(transfer.getWarehouse().getName());
        res.setReferenceNumber(transfer.getReferenceNumber());
        res.setSheetNumber(transfer.getSheetNumber());
        res.setTransferDate(transfer.getTransferDate());
        res.setRemarks(transfer.getRemarks());
        stockTransferService.findDeliveryReceipt(transfer).ifPresent(dr -> {
            res.setDeliveryReceiptId(dr.getId());
            res.setDeliveryReceiptReferenceNumber(dr.getReferenceNumber());
        });
        res.setCreatedAt(transfer.getCreatedAt());
        res.setCreatedBy(transfer.getCreatedBy());
        res.setVoided(transfer.isVoided());
        res.setVoidedAt(transfer.getVoidedAt());
        res.setVoidedBy(transfer.getVoidedBy());
        res.setLoaded(transfer.isLoaded());
        res.setOrigin(transfer.getOrigin());
        List<StockTransferLineResponse> lines = transfer.getLines().stream().map(this::toLineResponse).toList();
        res.setLines(lines);
        res.setTotalAmount(lines.stream().map(StockTransferLineResponse::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        return res;
    }

    private StockTransferLineResponse toLineResponse(StockTransferLine line) {
        StockTransferLineResponse res = new StockTransferLineResponse();
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
