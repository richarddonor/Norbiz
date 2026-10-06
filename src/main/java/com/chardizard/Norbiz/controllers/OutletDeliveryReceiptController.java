package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.OutletDeliveryReceiptLineResponse;
import com.chardizard.Norbiz.dto.OutletDeliveryReceiptRequest;
import com.chardizard.Norbiz.dto.OutletDeliveryReceiptResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.Employee;
import com.chardizard.Norbiz.models.OutletDeliveryReceipt;
import com.chardizard.Norbiz.models.OutletDeliveryReceiptLine;
import com.chardizard.Norbiz.services.OutletDeliveryReceiptService;
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

@Tag(name = "Outlet Delivery Receipts", description = "Outlet delivery receipt (ODR) transactions — requires VIEW_OUTLET_DELIVERY_RECEIPT / " +
        "CREATE_OUTLET_DELIVERY_RECEIPT / VOID_OUTLET_DELIVERY_RECEIPT permissions. Records an outlet's sales to (untracked) customers, " +
        "deducting on-hand stock from the outlet's own warehouse and crediting the sale to an agent for commission. Immutable once posted: " +
        "only create, view, and void are supported. Returned by Outlet Delivery Return.")
@RestController
@RequestMapping("/outlet-delivery-receipts")
@RequiredArgsConstructor
public class OutletDeliveryReceiptController {

    private final OutletDeliveryReceiptService outletDeliveryReceiptService;

    @Operation(summary = "List outlet delivery receipts", description = "Returns receipts belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Receipt list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_DELIVERY_RECEIPT permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_OUTLET_DELIVERY_RECEIPT')")
    public ResponseEntity<AppResponse<PageResponse<OutletDeliveryReceiptResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by outlet customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by agent (employee) ID") @RequestParam(required = false) Long agentId,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by delivery date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by delivery date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);

        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var receipts = outletDeliveryReceiptService.findAllForUser(userDetails.getUsername(), customerId, agentId, filters, fromInstant, toInstant, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(receipts)));
    }

    @Operation(summary = "Get outlet delivery receipt by ID")
    @ApiResponse(responseCode = "200", description = "Receipt returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_DELIVERY_RECEIPT permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Receipt not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_OUTLET_DELIVERY_RECEIPT')")
    public ResponseEntity<AppResponse<OutletDeliveryReceiptResponse>> getById(@Parameter(description = "Outlet delivery receipt ID") @PathVariable Long id,
                                                                              @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(outletDeliveryReceiptService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Post outlet delivery receipt", description = "Records an outlet's sale: the customer must be an active OUTLET, the agent an " +
            "active employee tagged AGENT, and each line an active INVENTORY item with quantity > 0. unitPrice defaults to the item's UNIT_PRICE. " +
            "Deducts on-hand stock from the outlet's warehouse.")
    @ApiResponse(responseCode = "201", description = "Receipt posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. non-outlet or inactive customer, non-agent employee, inactive item)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_OUTLET_DELIVERY_RECEIPT permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_OUTLET_DELIVERY_RECEIPT')")
    public ResponseEntity<AppResponse<OutletDeliveryReceiptResponse>> create(@Valid @RequestBody OutletDeliveryReceiptRequest request,
                                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(outletDeliveryReceiptService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Void outlet delivery receipt", description = "Cancels a receipt by reversing its ledger entries (giving the stock back to " +
            "the outlet warehouse). Fails if already voided or if an Outlet Delivery Return has returned any of it (void those first).")
    @ApiResponse(responseCode = "200", description = "Receipt voided")
    @ApiResponse(responseCode = "400", description = "Already voided, or has returns")
    @ApiResponse(responseCode = "403", description = "Missing VOID_OUTLET_DELIVERY_RECEIPT permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_OUTLET_DELIVERY_RECEIPT')")
    public ResponseEntity<AppResponse<OutletDeliveryReceiptResponse>> voidOutletDeliveryReceipt(@Parameter(description = "Outlet delivery receipt ID") @PathVariable Long id,
                                                                                                @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(outletDeliveryReceiptService.voidOutletDeliveryReceipt(id, userDetails.getUsername()))));
    }

    private OutletDeliveryReceiptResponse toResponse(OutletDeliveryReceipt receipt) {
        OutletDeliveryReceiptResponse res = new OutletDeliveryReceiptResponse();
        res.setId(receipt.getId());
        res.setCompanyId(receipt.getCompany().getId());
        res.setCompanyName(receipt.getCompany().getName());
        res.setCustomerId(receipt.getCustomer().getId());
        res.setCustomerName(receipt.getCustomer().getName());
        res.setWarehouseId(receipt.getWarehouse().getId());
        res.setWarehouseName(receipt.getWarehouse().getName());
        Employee agent = receipt.getAgent();
        res.setAgentId(agent.getId());
        res.setAgentCode(agent.getEmployeeCode());
        res.setAgentName(agent.getFirstName() + " " + agent.getLastName());
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
        List<OutletDeliveryReceiptLineResponse> lines = receipt.getLines().stream().map(this::toLineResponse).toList();
        res.setLines(lines);
        res.setTotalAmount(lines.stream().map(OutletDeliveryReceiptLineResponse::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        return res;
    }

    private OutletDeliveryReceiptLineResponse toLineResponse(OutletDeliveryReceiptLine line) {
        OutletDeliveryReceiptLineResponse res = new OutletDeliveryReceiptLineResponse();
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
