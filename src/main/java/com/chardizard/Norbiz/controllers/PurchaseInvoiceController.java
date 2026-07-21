package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.dto.PurchaseInvoiceFeeResponse;
import com.chardizard.Norbiz.dto.PurchaseInvoiceLineResponse;
import com.chardizard.Norbiz.dto.PurchaseInvoiceRequest;
import com.chardizard.Norbiz.dto.PurchaseInvoiceResponse;
import com.chardizard.Norbiz.models.PaymentStatus;
import com.chardizard.Norbiz.models.PurchaseInvoice;
import com.chardizard.Norbiz.models.PurchaseInvoiceFee;
import com.chardizard.Norbiz.models.PurchaseInvoiceLine;
import com.chardizard.Norbiz.services.PurchaseInvoiceService;
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
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "Purchase Invoices", description = "Purchase invoice transactions — requires VIEW_PURCHASE_INVOICE / CREATE_PURCHASE_INVOICE / VOID_PURCHASE_INVOICE permissions. " +
        "Invoices are immutable once posted: only create, view, and void are supported. Either invoice against an existing Purchase Order " +
        "(loaded in full, 1:1) or post a 'Direct' invoice with no backing PO (posts its own Transit Quantity movement). Line cost price and " +
        "all cost-derived totals are gated by VIEW_COST_PRICE.")
@RestController
@RequestMapping("/purchase-invoices")
@RequiredArgsConstructor
public class PurchaseInvoiceController {

    private final PurchaseInvoiceService purchaseInvoiceService;

    @Operation(summary = "List purchase invoices", description = "Returns invoices belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Invoice list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PURCHASE_INVOICE permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_PURCHASE_INVOICE')")
    public ResponseEntity<AppResponse<PageResponse<PurchaseInvoiceResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by warehouse ID") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "Filter by supplier ID") @RequestParam(required = false) Long supplierId,
            @Parameter(description = "Filter by payment status") @RequestParam(required = false) PaymentStatus paymentStatus,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by invoice date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by invoice date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);

        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        boolean canViewCostPrice = hasCostPriceAuthority(userDetails);
        var invoices = purchaseInvoiceService.findAllForUser(userDetails.getUsername(), warehouseId, supplierId, paymentStatus,
                        filters, fromInstant, toInstant, pageable)
                .map(i -> toResponse(i, canViewCostPrice));
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(invoices)));
    }

    @Operation(summary = "Get purchase invoice by ID")
    @ApiResponse(responseCode = "200", description = "Invoice returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_PURCHASE_INVOICE permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Invoice not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_PURCHASE_INVOICE')")
    public ResponseEntity<AppResponse<PurchaseInvoiceResponse>> getById(@Parameter(description = "Invoice ID") @PathVariable Long id,
                                                                         @AuthenticationPrincipal UserDetails userDetails) {
        PurchaseInvoice invoice = purchaseInvoiceService.findById(id, userDetails.getUsername());
        return ResponseEntity.ok(AppResponse.of(toResponse(invoice, hasCostPriceAuthority(userDetails))));
    }

    @Operation(summary = "Post purchase invoice", description = "Creates and posts a purchase invoice. Supply purchaseOrderId to invoice against an " +
            "existing Purchase Order (loaded in full, 1:1 — lines are taken from the PO, request lines only supply optional per-item discount " +
            "overrides). Omit purchaseOrderId for a 'Direct' invoice with no backing PO: lines are required and each line posts a Transit Quantity " +
            "increase, same as Purchase Order. Cost price is only honored for callers holding VIEW_COST_PRICE (forced to 0 otherwise).")
    @ApiResponse(responseCode = "201", description = "Invoice posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. PO already invoiced/voided, warehouse/supplier mismatch, non-positive quantity)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_PURCHASE_INVOICE permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_PURCHASE_INVOICE')")
    public ResponseEntity<AppResponse<PurchaseInvoiceResponse>> create(@Valid @RequestBody PurchaseInvoiceRequest request,
                                                                        @AuthenticationPrincipal UserDetails userDetails) {
        boolean canViewCostPrice = hasCostPriceAuthority(userDetails);
        PurchaseInvoice saved = purchaseInvoiceService.create(request, userDetails.getUsername(), canViewCostPrice);
        return ResponseEntity.status(HttpStatus.CREATED).body(AppResponse.of(toResponse(saved, canViewCostPrice)));
    }

    @Operation(summary = "Void purchase invoice", description = "Cancels an invoice. Direct-mode invoices reverse the transit quantity they posted; " +
            "PO-based invoices unload the originating Purchase Order so it becomes voidable/invoiceable again. Fails if already voided or if the " +
            "invoice has been loaded (fully or partially) into a downstream transaction.")
    @ApiResponse(responseCode = "200", description = "Invoice voided")
    @ApiResponse(responseCode = "400", description = "Already voided, or has been loaded")
    @ApiResponse(responseCode = "403", description = "Missing VOID_PURCHASE_INVOICE permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_PURCHASE_INVOICE')")
    public ResponseEntity<AppResponse<PurchaseInvoiceResponse>> voidInvoice(@Parameter(description = "Invoice ID") @PathVariable Long id,
                                                                             @AuthenticationPrincipal UserDetails userDetails) {
        PurchaseInvoice voided = purchaseInvoiceService.voidPurchaseInvoice(id, userDetails.getUsername());
        return ResponseEntity.ok(AppResponse.of(toResponse(voided, hasCostPriceAuthority(userDetails))));
    }

    private boolean hasCostPriceAuthority(UserDetails userDetails) {
        return userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("VIEW_COST_PRICE"));
    }

    private PurchaseInvoiceResponse toResponse(PurchaseInvoice invoice, boolean canViewCostPrice) {
        PurchaseInvoiceResponse res = new PurchaseInvoiceResponse();
        res.setId(invoice.getId());
        res.setCompanyId(invoice.getCompany().getId());
        res.setCompanyName(invoice.getCompany().getName());
        res.setWarehouseId(invoice.getWarehouse().getId());
        res.setWarehouseName(invoice.getWarehouse().getName());
        res.setSupplierId(invoice.getSupplier().getId());
        res.setSupplierName(invoice.getSupplier().getName());
        if (invoice.getPurchaseOrder() != null) {
            res.setPurchaseOrderId(invoice.getPurchaseOrder().getId());
            res.setPurchaseOrderReferenceNumber(invoice.getPurchaseOrder().getReferenceNumber());
        }
        res.setReferenceNumber(invoice.getReferenceNumber());
        res.setSheetNumber(invoice.getSheetNumber());
        res.setInvoiceDate(invoice.getInvoiceDate());
        res.setRemarks(invoice.getRemarks());
        res.setDiscountPercentage(invoice.getDiscountPercentage());
        res.setPaymentStatus(invoice.getPaymentStatus());
        res.setCreatedAt(invoice.getCreatedAt());
        res.setCreatedBy(invoice.getCreatedBy());
        res.setVoided(invoice.isVoided());
        res.setVoidedAt(invoice.getVoidedAt());
        res.setVoidedBy(invoice.getVoidedBy());
        res.setLoaded(invoice.isLoaded());
        res.setLines(invoice.getLines().stream().map(l -> toLineResponse(l, canViewCostPrice)).collect(Collectors.toList()));
        res.setFees(invoice.getFees().stream().map(this::toFeeResponse).collect(Collectors.toList()));

        BigDecimal feesTotal = invoice.getFees().stream()
                .map(PurchaseInvoiceFee::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        res.setFeesTotal(feesTotal);

        if (canViewCostPrice) {
            BigDecimal itemsSubtotal = res.getLines().stream()
                    .map(PurchaseInvoiceLineResponse::getLineNet)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal discountAmount = itemsSubtotal
                    .multiply(invoice.getDiscountPercentage())
                    .divide(BigDecimal.valueOf(100));
            res.setItemsSubtotal(itemsSubtotal);
            res.setDiscountAmount(discountAmount);
            res.setNetPayable(itemsSubtotal.subtract(discountAmount).add(feesTotal));
        }

        return res;
    }

    private PurchaseInvoiceLineResponse toLineResponse(PurchaseInvoiceLine line, boolean canViewCostPrice) {
        PurchaseInvoiceLineResponse res = new PurchaseInvoiceLineResponse();
        res.setId(line.getId());
        res.setItemId(line.getItem().getId());
        res.setItemCode(line.getItem().getItemCode());
        res.setItemName(line.getItem().getName());
        if (line.getPurchaseOrderLine() != null) {
            res.setPurchaseOrderLineId(line.getPurchaseOrderLine().getId());
        }
        res.setQuantity(line.getQuantity());
        res.setDiscountPercentage(line.getDiscountPercentage());
        res.setQuantityLoaded(line.getQuantityLoaded());
        if (canViewCostPrice) {
            res.setCostPrice(line.getCostPrice());
            BigDecimal lineSubtotal = line.getQuantity().multiply(line.getCostPrice());
            BigDecimal lineDiscount = lineSubtotal.multiply(line.getDiscountPercentage()).divide(BigDecimal.valueOf(100));
            res.setLineNet(lineSubtotal.subtract(lineDiscount));
        }
        return res;
    }

    private PurchaseInvoiceFeeResponse toFeeResponse(PurchaseInvoiceFee fee) {
        PurchaseInvoiceFeeResponse res = new PurchaseInvoiceFeeResponse();
        res.setId(fee.getId());
        res.setDescription(fee.getDescription());
        res.setAmount(fee.getAmount());
        return res;
    }
}
