package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.services.OpenItemQueryService;
import com.chardizard.Norbiz.services.SalesInvoiceReturnService;
import com.chardizard.Norbiz.util.DateRangeUtils;
import com.chardizard.Norbiz.util.ListSort;
import com.chardizard.Norbiz.util.ListTotals;
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
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Tag(name = "Sales Invoice Returns", description = "Sales invoice returns — requires VIEW_SALES_INVOICE_RETURN / CREATE_SALES_INVOICE_RETURN / "
        + "VOID_SALES_INVOICE_RETURN permissions. A customer returns items billed on one or more of its Sales Invoices: the stock comes back "
        + "into the main warehouse and the customer is credited with what the items were billed at (applied to the returned invoices by "
        + "default). Supports partial returns. Immutable once posted: only create, view, and void.")
@RestController
@RequestMapping("/sales-invoice-returns")
@RequiredArgsConstructor
public class SalesInvoiceReturnController {

    // Column key → entity path for ?sort= (ListSort); anything else is a 400.
    private static final Map<String, String> SORTABLE = ListSort.fields(
            "company", "company.name",
            "referenceNumber", "referenceNumber",
            "sheetNumber", "sheetNumber",
            "voided", "voided",
            "customer", "customer.name",
            "warehouse", "warehouse.name",
            "date", "returnDate",
            "netAmount", "netAmount",
            "unapplied", "openItem.balance");

    private final SalesInvoiceReturnService returnService;
    private final OpenItemQueryService openItemQueryService;

    @Operation(summary = "List sales invoice returns", description = "Returns belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Return list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_SALES_INVOICE_RETURN permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_SALES_INVOICE_RETURN')")
    public ResponseEntity<AppResponse<PageResponse<SalesInvoiceReturnResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by returned sales invoice ID (any line)") @RequestParam(required = false) Long salesInvoiceId,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by customer name (contains)") @RequestParam(required = false) String customer,
            @Parameter(description = "Filter by remarks (contains)") @RequestParam(required = false) String remarks,
            @Parameter(description = "Filter by voided status (true or false)") @RequestParam(required = false) String voided,
            @Parameter(description = "Filter by return date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by return date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            @Parameter(description = "Filter by origin: NATIVE or MIGRATED (copied from legacy)") @RequestParam(required = false) TransactionOrigin origin,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals,
            Pageable pageable) {
        Pageable sorted = ListSort.apply(pageable, SORTABLE, ListSort.CREATED_FIRST);
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);
        if (StringUtils.hasText(customer)) filters.put("customer", customer);
        if (StringUtils.hasText(remarks)) filters.put("remarks", remarks);
        if (StringUtils.hasText(voided)) filters.put("voided", voided);
        if (origin != null) filters.put("origin", origin.name());
        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var page = returnService.findAllForUser(userDetails.getUsername(), customerId, salesInvoiceId, filters, fromInstant, toInstant, sorted)
                .map(r -> toResponse(r, false));
        var response = PageResponse.of(page);
        if (totals) {
            response.withTotals(ListTotals.within(page.getTotalElements(), () -> returnService.findAllForUser(userDetails.getUsername(),
                            customerId, salesInvoiceId, filters, fromInstant, toInstant, ListSort.unpaged(sorted)).map(r -> toResponse(r, false)).getContent(),
                    ListTotals.column("netAmount", SalesInvoiceReturnResponse::getNetAmount),
                    ListTotals.column("unapplied", SalesInvoiceReturnResponse::getUnappliedAmount)));
        }
        return ResponseEntity.ok(AppResponse.of(response));
    }

    @Operation(summary = "Get sales invoice return by ID", description = "Includes the applications the return's credit made.")
    @ApiResponse(responseCode = "200", description = "Return returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_SALES_INVOICE_RETURN permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Return not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_SALES_INVOICE_RETURN')")
    public ResponseEntity<AppResponse<SalesInvoiceReturnResponse>> getById(@Parameter(description = "Sales invoice return ID") @PathVariable Long id,
                                                                           @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(returnService.findById(id, userDetails.getUsername()), true)));
    }

    @Operation(summary = "Post sales invoice return", description = "Returns (part of) the items on one customer's Sales Invoices: each line "
            + "names an invoice line and the quantity coming back, at most what is still unreturned on it. The stock comes back into the main "
            + "warehouse. The customer is credited with the invoice lines' net unit price × quantity; unless applyToInvoices is false, the credit "
            + "is applied to each returned invoice's open balance, and anything left stays on the customer's account as unapplied credit.")
    @ApiResponse(responseCode = "201", description = "Return posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. voided invoice, another customer's invoice, over-return, before the "
            + "accounting start date)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_SALES_INVOICE_RETURN permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_SALES_INVOICE_RETURN')")
    public ResponseEntity<AppResponse<SalesInvoiceReturnResponse>> create(@Valid @RequestBody SalesInvoiceReturnRequest request,
                                                                          @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(returnService.create(request, userDetails.getUsername()), true)));
    }

    @Operation(summary = "Void sales invoice return", description = "Takes the stock back out of the main warehouse, reverses the applications "
            + "the return's credit made and reopens the invoice lines by the voided quantities. Fails if already voided, if the stock is no "
            + "longer on hand, or while a later payment/refund uses the credit (void that first).")
    @ApiResponse(responseCode = "200", description = "Return voided")
    @ApiResponse(responseCode = "400", description = "Already voided, stock not on hand, or credit used by another document")
    @ApiResponse(responseCode = "403", description = "Missing VOID_SALES_INVOICE_RETURN permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_SALES_INVOICE_RETURN')")
    public ResponseEntity<AppResponse<SalesInvoiceReturnResponse>> voidReturn(@Parameter(description = "Sales invoice return ID") @PathVariable Long id,
                                                                              @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(returnService.voidReturn(id, userDetails.getUsername()), true)));
    }

    private SalesInvoiceReturnResponse toResponse(SalesInvoiceReturn ret, boolean withApplications) {
        SalesInvoiceReturnResponse res = new SalesInvoiceReturnResponse();
        res.setId(ret.getId());
        res.setCompanyId(ret.getCompany().getId());
        res.setCompanyName(ret.getCompany().getName());
        res.setCustomerId(ret.getCustomer().getId());
        res.setCustomerName(ret.getCustomer().getName());
        res.setWarehouseId(ret.getWarehouse().getId());
        res.setWarehouseName(ret.getWarehouse().getName());
        res.setReferenceNumber(ret.getReferenceNumber());
        res.setSheetNumber(ret.getSheetNumber());
        res.setReturnDate(ret.getReturnDate());
        res.setRemarks(ret.getRemarks());
        res.setNetAmount(ret.getNetAmount());
        OpenItem credit = ret.getOpenItem();
        if (credit != null) {
            res.setOpenItemId(credit.getId());
            res.setAppliedAmount(credit.getAppliedAmount());
            res.setUnappliedAmount(credit.getBalance());
        }
        res.setCreatedAt(ret.getCreatedAt());
        res.setCreatedBy(ret.getCreatedBy());
        res.setVoided(ret.isVoided());
        res.setVoidedAt(ret.getVoidedAt());
        res.setVoidedBy(ret.getVoidedBy());
        res.setOrigin(ret.getOrigin());
        List<SalesInvoiceReturnLineResponse> lines = ret.getLines().stream().map(this::toLineResponse).toList();
        res.setLines(lines);
        res.setSalesInvoiceReferenceNumbers(lines.stream()
                .map(SalesInvoiceReturnLineResponse::getSalesInvoiceReferenceNumber)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(", ")));
        if (withApplications) {
            res.setApplications(openItemQueryService.allocationsBySource(TransactionType.SALES_INVOICE_RETURN, ret.getId()).stream()
                    .map(AccountingResponses::allocation).toList());
        }
        return res;
    }

    private SalesInvoiceReturnLineResponse toLineResponse(SalesInvoiceReturnLine line) {
        SalesInvoiceReturnLineResponse res = new SalesInvoiceReturnLineResponse();
        res.setId(line.getId());
        res.setItemId(line.getItem().getId());
        res.setItemCode(line.getItem().getItemCode());
        res.setItemName(line.getItem().getName());
        SalesInvoiceLine invoiceLine = line.getSalesInvoiceLine();
        if (invoiceLine != null) {
            res.setSalesInvoiceLineId(invoiceLine.getId());
            res.setSalesInvoiceId(invoiceLine.getSalesInvoice().getId());
            res.setSalesInvoiceReferenceNumber(invoiceLine.getSalesInvoice().getReferenceNumber());
        }
        res.setQuantity(line.getQuantity());
        res.setUnitPrice(line.getUnitPrice());
        res.setAmount(line.getQuantity().multiply(line.getUnitPrice()));
        res.setLineNumber(line.getLineNumber());
        res.setPostsStock(line.isPostsStock());
        return res;
    }
}
