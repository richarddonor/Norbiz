
package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.models.TransactionOrigin;
import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.dto.PurchaseReceiptLineResponse;
import com.chardizard.Norbiz.dto.PurchaseReceiptRequest;
import com.chardizard.Norbiz.dto.PurchaseReceiptResponse;
import com.chardizard.Norbiz.models.PurchaseReceipt;
import com.chardizard.Norbiz.models.PurchaseReceiptLine;
import com.chardizard.Norbiz.services.PurchaseReceiptService;
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

@Tag(name = "Purchase Receipts", description = "Purchase receipt transactions — requires VIEW_PURCHASE_RECEIPT / CREATE_PURCHASE_RECEIPT / VOID_PURCHASE_RECEIPT " +
        "permissions. Receipts are immutable once posted: only create, view, and void are supported. Each receipt is posted against the intransit " +
        "quantity of either a Purchase Order or a Direct-mode Purchase Invoice (PO-based invoices have nothing of their own to receipt), deducting " +
        "Transit Quantity and adding to main Quantity. Supports partial receiving across multiple receipts.")
@RestController
@RequestMapping("/purchase-receipts")
@RequiredArgsConstructor
public class PurchaseReceiptController {

    private final PurchaseReceiptService purchaseReceiptService;

    @Operation(summary = "List purchase receipts", description = "Returns receipts belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Receipt list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PURCHASE_RECEIPT permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_PURCHASE_RECEIPT')")
    public ResponseEntity<AppResponse<PageResponse<PurchaseReceiptResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by warehouse ID") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "Filter by supplier ID") @RequestParam(required = false) Long supplierId,
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

        var receipts = purchaseReceiptService.findAllForUser(userDetails.getUsername(), warehouseId, supplierId,
                        filters, fromInstant, toInstant, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(receipts)));
    }

    @Operation(summary = "Get purchase receipt by ID")
    @ApiResponse(responseCode = "200", description = "Receipt returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PURCHASE_RECEIPT permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Receipt not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_PURCHASE_RECEIPT')")
    public ResponseEntity<AppResponse<PurchaseReceiptResponse>> getById(@Parameter(description = "Receipt ID") @PathVariable Long id,
                                                                          @AuthenticationPrincipal UserDetails userDetails) {
        PurchaseReceipt receipt = purchaseReceiptService.findById(id, userDetails.getUsername());
        return ResponseEntity.ok(AppResponse.of(toResponse(receipt)));
    }

    @Operation(summary = "Post purchase receipt", description = "Creates and posts a purchase receipt against the outstanding intransit quantity of " +
            "either a Purchase Order (purchaseOrderId) or a Direct-mode Purchase Invoice (purchaseInvoiceId) — exactly one must be supplied. Each line's " +
            "quantity must not exceed that item's outstanding (quantity - quantityLoaded) amount on the source. Posting immediately writes ledger entries " +
            "that deduct Transit Quantity and add to main Quantity, and marks the source fully loaded once every line is fully received.")
    @ApiResponse(responseCode = "201", description = "Receipt posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. source voided/already fully processed, warehouse/supplier mismatch, quantity exceeds outstanding)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_PURCHASE_RECEIPT permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_PURCHASE_RECEIPT')")
    public ResponseEntity<AppResponse<PurchaseReceiptResponse>> create(@Valid @RequestBody PurchaseReceiptRequest request,
                                                                         @AuthenticationPrincipal UserDetails userDetails) {
        PurchaseReceipt saved = purchaseReceiptService.create(request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(AppResponse.of(toResponse(saved)));
    }

    @Operation(summary = "Void purchase receipt", description = "Cancels a receipt: reverses the goods movement it posted (giving quantity back to " +
            "Transit Quantity) and un-loads the source (PO or Direct invoice) by the voided amount, so it becomes receivable/invoiceable again. " +
            "Fails if already voided.")
    @ApiResponse(responseCode = "200", description = "Receipt voided")
    @ApiResponse(responseCode = "400", description = "Already voided")
    @ApiResponse(responseCode = "403", description = "Missing VOID_PURCHASE_RECEIPT permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_PURCHASE_RECEIPT')")
    public ResponseEntity<AppResponse<PurchaseReceiptResponse>> voidReceipt(@Parameter(description = "Receipt ID") @PathVariable Long id,
                                                                              @AuthenticationPrincipal UserDetails userDetails) {
        PurchaseReceipt voided = purchaseReceiptService.voidPurchaseReceipt(id, userDetails.getUsername());
        return ResponseEntity.ok(AppResponse.of(toResponse(voided)));
    }

    private PurchaseReceiptResponse toResponse(PurchaseReceipt receipt) {
        PurchaseReceiptResponse res = new PurchaseReceiptResponse();
        res.setId(receipt.getId());
        res.setCompanyId(receipt.getCompany().getId());
        res.setCompanyName(receipt.getCompany().getName());
        res.setWarehouseId(receipt.getWarehouse().getId());
        res.setWarehouseName(receipt.getWarehouse().getName());
        res.setSupplierId(receipt.getSupplier().getId());
        res.setSupplierName(receipt.getSupplier().getName());
        if (receipt.getPurchaseOrder() != null) {
            res.setPurchaseOrderId(receipt.getPurchaseOrder().getId());
            res.setPurchaseOrderReferenceNumber(receipt.getPurchaseOrder().getReferenceNumber());
        }
        if (receipt.getPurchaseInvoice() != null) {
            res.setPurchaseInvoiceId(receipt.getPurchaseInvoice().getId());
            res.setPurchaseInvoiceReferenceNumber(receipt.getPurchaseInvoice().getReferenceNumber());
        }
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

    private PurchaseReceiptLineResponse toLineResponse(PurchaseReceiptLine line) {
        PurchaseReceiptLineResponse res = new PurchaseReceiptLineResponse();
        res.setId(line.getId());
        res.setItemId(line.getItem().getId());
        res.setItemCode(line.getItem().getItemCode());
        res.setItemName(line.getItem().getName());
        if (line.getPurchaseOrderLine() != null) {
            res.setPurchaseOrderLineId(line.getPurchaseOrderLine().getId());
        }
        if (line.getPurchaseInvoiceLine() != null) {
            res.setPurchaseInvoiceLineId(line.getPurchaseInvoiceLine().getId());
        }
        res.setQuantity(line.getQuantity());
        res.setLineNumber(line.getLineNumber());
        res.setQuantityLoaded(line.getQuantityLoaded());
        return res;
    }
}
