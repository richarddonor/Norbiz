
package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.dto.PurchaseReceiveLineResponse;
import com.chardizard.Norbiz.dto.PurchaseReceiveRequest;
import com.chardizard.Norbiz.dto.PurchaseReceiveResponse;
import com.chardizard.Norbiz.models.PurchaseReceive;
import com.chardizard.Norbiz.models.PurchaseReceiveLine;
import com.chardizard.Norbiz.services.PurchaseReceiveService;
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

@Tag(name = "Purchase Receives", description = "Purchase receive transactions — requires VIEW_PURCHASE_RECEIVE / CREATE_PURCHASE_RECEIVE / VOID_PURCHASE_RECEIVE " +
        "permissions. Receives are immutable once posted: only create, view, and void are supported. Each receive is posted against the intransit " +
        "quantity of either a Purchase Order or a Direct-mode Purchase Invoice (PO-based invoices have nothing of their own to receive), deducting " +
        "Transit Quantity and adding to main Quantity. Supports partial receiving across multiple receives.")
@RestController
@RequestMapping("/purchase-receives")
@RequiredArgsConstructor
public class PurchaseReceiveController {

    private final PurchaseReceiveService purchaseReceiveService;

    @Operation(summary = "List purchase receives", description = "Returns receives belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Receive list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PURCHASE_RECEIVE permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_PURCHASE_RECEIVE')")
    public ResponseEntity<AppResponse<PageResponse<PurchaseReceiveResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by warehouse ID") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "Filter by supplier ID") @RequestParam(required = false) Long supplierId,
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

        var receives = purchaseReceiveService.findAllForUser(userDetails.getUsername(), warehouseId, supplierId,
                        filters, fromInstant, toInstant, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(receives)));
    }

    @Operation(summary = "Get purchase receive by ID")
    @ApiResponse(responseCode = "200", description = "Receive returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PURCHASE_RECEIVE permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Receive not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_PURCHASE_RECEIVE')")
    public ResponseEntity<AppResponse<PurchaseReceiveResponse>> getById(@Parameter(description = "Receive ID") @PathVariable Long id,
                                                                          @AuthenticationPrincipal UserDetails userDetails) {
        PurchaseReceive receive = purchaseReceiveService.findById(id, userDetails.getUsername());
        return ResponseEntity.ok(AppResponse.of(toResponse(receive)));
    }

    @Operation(summary = "Post purchase receive", description = "Creates and posts a purchase receive against the outstanding intransit quantity of " +
            "either a Purchase Order (purchaseOrderId) or a Direct-mode Purchase Invoice (purchaseInvoiceId) — exactly one must be supplied. Each line's " +
            "quantity must not exceed that item's outstanding (quantity - quantityLoaded) amount on the source. Posting immediately writes ledger entries " +
            "that deduct Transit Quantity and add to main Quantity, and marks the source fully loaded once every line is fully received.")
    @ApiResponse(responseCode = "201", description = "Receive posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. source voided/already fully processed, warehouse/supplier mismatch, quantity exceeds outstanding)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_PURCHASE_RECEIVE permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_PURCHASE_RECEIVE')")
    public ResponseEntity<AppResponse<PurchaseReceiveResponse>> create(@Valid @RequestBody PurchaseReceiveRequest request,
                                                                         @AuthenticationPrincipal UserDetails userDetails) {
        PurchaseReceive saved = purchaseReceiveService.create(request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(AppResponse.of(toResponse(saved)));
    }

    @Operation(summary = "Void purchase receive", description = "Cancels a receive: reverses the goods movement it posted (giving quantity back to " +
            "Transit Quantity) and un-loads the source (PO or Direct invoice) by the voided amount, so it becomes receivable/invoiceable again. " +
            "Fails if already voided.")
    @ApiResponse(responseCode = "200", description = "Receive voided")
    @ApiResponse(responseCode = "400", description = "Already voided")
    @ApiResponse(responseCode = "403", description = "Missing VOID_PURCHASE_RECEIVE permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_PURCHASE_RECEIVE')")
    public ResponseEntity<AppResponse<PurchaseReceiveResponse>> voidReceive(@Parameter(description = "Receive ID") @PathVariable Long id,
                                                                              @AuthenticationPrincipal UserDetails userDetails) {
        PurchaseReceive voided = purchaseReceiveService.voidPurchaseReceive(id, userDetails.getUsername());
        return ResponseEntity.ok(AppResponse.of(toResponse(voided)));
    }

    private PurchaseReceiveResponse toResponse(PurchaseReceive receive) {
        PurchaseReceiveResponse res = new PurchaseReceiveResponse();
        res.setId(receive.getId());
        res.setCompanyId(receive.getCompany().getId());
        res.setCompanyName(receive.getCompany().getName());
        res.setWarehouseId(receive.getWarehouse().getId());
        res.setWarehouseName(receive.getWarehouse().getName());
        res.setSupplierId(receive.getSupplier().getId());
        res.setSupplierName(receive.getSupplier().getName());
        if (receive.getPurchaseOrder() != null) {
            res.setPurchaseOrderId(receive.getPurchaseOrder().getId());
            res.setPurchaseOrderReferenceNumber(receive.getPurchaseOrder().getReferenceNumber());
        }
        if (receive.getPurchaseInvoice() != null) {
            res.setPurchaseInvoiceId(receive.getPurchaseInvoice().getId());
            res.setPurchaseInvoiceReferenceNumber(receive.getPurchaseInvoice().getReferenceNumber());
        }
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

    private PurchaseReceiveLineResponse toLineResponse(PurchaseReceiveLine line) {
        PurchaseReceiveLineResponse res = new PurchaseReceiveLineResponse();
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
        res.setQuantityLoaded(line.getQuantityLoaded());
        return res;
    }
}
