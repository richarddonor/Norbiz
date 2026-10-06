package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.OutletDeliveryReturnLineResponse;
import com.chardizard.Norbiz.dto.OutletDeliveryReturnRequest;
import com.chardizard.Norbiz.dto.OutletDeliveryReturnResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.Employee;
import com.chardizard.Norbiz.models.OutletDeliveryReturn;
import com.chardizard.Norbiz.models.OutletDeliveryReturnLine;
import com.chardizard.Norbiz.services.OutletDeliveryReturnService;
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

@Tag(name = "Outlet Delivery Returns", description = "Outlet delivery return transactions — requires VIEW_OUTLET_DELIVERY_RETURN / " +
        "CREATE_OUTLET_DELIVERY_RETURN / VOID_OUTLET_DELIVERY_RETURN permissions. Each return is posted against an Outlet Delivery Receipt, " +
        "adding the returned items back to the outlet warehouse's on-hand stock. Supports partial returns. Immutable once posted.")
@RestController
@RequestMapping("/outlet-delivery-returns")
@RequiredArgsConstructor
public class OutletDeliveryReturnController {

    private final OutletDeliveryReturnService outletDeliveryReturnService;

    @Operation(summary = "List outlet delivery returns", description = "Returns belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Return list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_DELIVERY_RETURN permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_OUTLET_DELIVERY_RETURN')")
    public ResponseEntity<AppResponse<PageResponse<OutletDeliveryReturnResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by outlet customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by agent (employee) ID") @RequestParam(required = false) Long agentId,
            @Parameter(description = "Filter by outlet delivery receipt ID") @RequestParam(required = false) Long outletDeliveryReceiptId,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by return date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by return date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);

        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var returns = outletDeliveryReturnService.findAllForUser(userDetails.getUsername(), customerId, agentId, outletDeliveryReceiptId,
                        filters, fromInstant, toInstant, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(returns)));
    }

    @Operation(summary = "Get outlet delivery return by ID")
    @ApiResponse(responseCode = "200", description = "Return returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_OUTLET_DELIVERY_RETURN permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Return not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_OUTLET_DELIVERY_RETURN')")
    public ResponseEntity<AppResponse<OutletDeliveryReturnResponse>> getById(@Parameter(description = "Outlet delivery return ID") @PathVariable Long id,
                                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(outletDeliveryReturnService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Post outlet delivery return", description = "Returns (part of) an Outlet Delivery Receipt's sold items to the outlet " +
            "warehouse's on-hand stock. Outlet, warehouse, agent and unit prices are copied from the receipt. Each line's quantity must not exceed " +
            "that item's outstanding (not yet returned) amount on the receipt.")
    @ApiResponse(responseCode = "201", description = "Return posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. voided/fully returned receipt, over-return)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_OUTLET_DELIVERY_RETURN permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_OUTLET_DELIVERY_RETURN')")
    public ResponseEntity<AppResponse<OutletDeliveryReturnResponse>> create(@Valid @RequestBody OutletDeliveryReturnRequest request,
                                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(outletDeliveryReturnService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Void outlet delivery return", description = "Cancels a return by reversing its ledger entries (taking the stock back out " +
            "of the outlet warehouse) and reopening the Outlet Delivery Receipt by the voided amounts. Fails if already voided.")
    @ApiResponse(responseCode = "200", description = "Return voided")
    @ApiResponse(responseCode = "400", description = "Already voided")
    @ApiResponse(responseCode = "403", description = "Missing VOID_OUTLET_DELIVERY_RETURN permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_OUTLET_DELIVERY_RETURN')")
    public ResponseEntity<AppResponse<OutletDeliveryReturnResponse>> voidOutletDeliveryReturn(@Parameter(description = "Outlet delivery return ID") @PathVariable Long id,
                                                                                              @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(outletDeliveryReturnService.voidOutletDeliveryReturn(id, userDetails.getUsername()))));
    }

    private OutletDeliveryReturnResponse toResponse(OutletDeliveryReturn ret) {
        OutletDeliveryReturnResponse res = new OutletDeliveryReturnResponse();
        res.setId(ret.getId());
        res.setCompanyId(ret.getCompany().getId());
        res.setCompanyName(ret.getCompany().getName());
        res.setOutletDeliveryReceiptId(ret.getOutletDeliveryReceipt().getId());
        res.setOutletDeliveryReceiptReferenceNumber(ret.getOutletDeliveryReceipt().getReferenceNumber());
        res.setCustomerId(ret.getCustomer().getId());
        res.setCustomerName(ret.getCustomer().getName());
        res.setWarehouseId(ret.getWarehouse().getId());
        res.setWarehouseName(ret.getWarehouse().getName());
        Employee agent = ret.getAgent();
        res.setAgentId(agent.getId());
        res.setAgentCode(agent.getEmployeeCode());
        res.setAgentName(agent.getFirstName() + " " + agent.getLastName());
        res.setReferenceNumber(ret.getReferenceNumber());
        res.setSheetNumber(ret.getSheetNumber());
        res.setReturnDate(ret.getReturnDate());
        res.setRemarks(ret.getRemarks());
        res.setCreatedAt(ret.getCreatedAt());
        res.setCreatedBy(ret.getCreatedBy());
        res.setVoided(ret.isVoided());
        res.setVoidedAt(ret.getVoidedAt());
        res.setVoidedBy(ret.getVoidedBy());
        res.setLoaded(ret.isLoaded());
        List<OutletDeliveryReturnLineResponse> lines = ret.getLines().stream().map(this::toLineResponse).toList();
        res.setLines(lines);
        res.setTotalAmount(lines.stream().map(OutletDeliveryReturnLineResponse::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        return res;
    }

    private OutletDeliveryReturnLineResponse toLineResponse(OutletDeliveryReturnLine line) {
        OutletDeliveryReturnLineResponse res = new OutletDeliveryReturnLineResponse();
        res.setId(line.getId());
        res.setItemId(line.getItem().getId());
        res.setItemCode(line.getItem().getItemCode());
        res.setItemName(line.getItem().getName());
        res.setOutletDeliveryReceiptLineId(line.getOutletDeliveryReceiptLine().getId());
        res.setQuantity(line.getQuantity());
        res.setUnitPrice(line.getUnitPrice());
        res.setAmount(line.getQuantity().multiply(line.getUnitPrice()));
        res.setLineNumber(line.getLineNumber());
        res.setQuantityLoaded(line.getQuantityLoaded());
        return res;
    }
}
