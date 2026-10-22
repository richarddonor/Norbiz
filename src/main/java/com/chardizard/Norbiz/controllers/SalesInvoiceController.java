package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.services.SalesInvoiceService;
import com.chardizard.Norbiz.util.AccountingAmounts;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Sales Invoices", description = "Sales invoice transactions — requires VIEW_SALES_INVOICE / CREATE_SALES_INVOICE / VOID_SALES_INVOICE permissions. "
        + "Bills a customer and opens a receivable for its net amount. Either bill a plain-customer Delivery Receipt in full (lines copied, "
        + "no stock effect) or post a 'Direct' invoice whose lines deduct the company's main warehouse stock. Immutable once posted: only "
        + "create, view, and void.")
@RestController
@RequestMapping("/sales-invoices")
@RequiredArgsConstructor
public class SalesInvoiceController {

    // Column key → entity path for ?sort= (ListSort); anything else is a 400.
    private static final Map<String, String> SORTABLE = ListSort.fields(
            "company", "company.name",
            "referenceNumber", "referenceNumber",
            "sheetNumber", "sheetNumber",
            "voided", "voided",
            "customer", "customer.name",
            "warehouse", "warehouse.name",
            "deliveryReceipt", "deliveryReceipt.referenceNumber",
            "date", "invoiceDate",
            "dueDate", "dueDate",
            "netAmount", "netAmount",
            "balance", "openItem.balance",
            "paymentStatus", "paymentStatus");

    private final SalesInvoiceService salesInvoiceService;

    @Operation(summary = "List sales invoices", description = "Returns invoices belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Invoice list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_SALES_INVOICE permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_SALES_INVOICE')")
    public ResponseEntity<AppResponse<PageResponse<SalesInvoiceResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by payment status") @RequestParam(required = false) PaymentStatus paymentStatus,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by customer name (contains)") @RequestParam(required = false) String customer,
            @Parameter(description = "Filter by billed delivery receipt reference (contains)") @RequestParam(required = false) String deliveryReceipt,
            @Parameter(description = "Filter by voided status (true or false)") @RequestParam(required = false) String voided,
            @Parameter(description = "Filter by invoice date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by invoice date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            @Parameter(description = "Filter by origin") @RequestParam(required = false) TransactionOrigin origin,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals,
            Pageable pageable) {
        Pageable sorted = ListSort.apply(pageable, SORTABLE, ListSort.CREATED_FIRST);
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);
        if (StringUtils.hasText(customer)) filters.put("customer", customer);
        if (StringUtils.hasText(deliveryReceipt)) filters.put("deliveryReceipt", deliveryReceipt);
        if (StringUtils.hasText(voided)) filters.put("voided", voided);
        if (origin != null) filters.put("origin", origin.name());
        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var page = salesInvoiceService.findAllForUser(userDetails.getUsername(), customerId, paymentStatus, filters, fromInstant, toInstant, sorted)
                .map(this::toResponse);
        var response = PageResponse.of(page);
        if (totals) {
            response.withTotals(ListTotals.within(page.getTotalElements(), () -> salesInvoiceService.findAllForUser(userDetails.getUsername(),
                            customerId, paymentStatus, filters, fromInstant, toInstant, ListSort.unpaged(sorted)).map(this::toResponse).getContent(),
                    ListTotals.column("netAmount", SalesInvoiceResponse::getNetAmount),
                    ListTotals.column("balance", SalesInvoiceResponse::getBalance)));
        }
        return ResponseEntity.ok(AppResponse.of(response));
    }

    @Operation(summary = "Get sales invoice by ID")
    @ApiResponse(responseCode = "200", description = "Invoice returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_SALES_INVOICE permission or no access to company")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_SALES_INVOICE')")
    public ResponseEntity<AppResponse<SalesInvoiceResponse>> getById(@Parameter(description = "Invoice ID") @PathVariable Long id,
                                                                     @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(salesInvoiceService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Post sales invoice", description = "Supply deliveryReceiptId to bill a plain-customer Delivery Receipt in full (lines are copied; "
            + "request lines only supply optional per-item discount overrides). Omit it for a 'Direct' invoice: customerId and lines are required, "
            + "each line must be an active INVENTORY item on hand in the main warehouse, and unit price defaults to the item's UNIT_PRICE. "
            + "Dated on/after the accounting start date; due date defaults to invoice date + the customer's terms.")
    @ApiResponse(responseCode = "201", description = "Invoice posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. DR already billed/voided/to an outlet, before the accounting start date)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_SALES_INVOICE permission or no access to company")
    @ApiResponse(responseCode = "409", description = "Insufficient stock (code INSUFFICIENT_STOCK)")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_SALES_INVOICE')")
    public ResponseEntity<AppResponse<SalesInvoiceResponse>> create(@Valid @RequestBody SalesInvoiceRequest request,
                                                                    @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(salesInvoiceService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Void sales invoice", description = "Cancels an invoice: Direct invoices return their stock to the main warehouse; DR-based ones "
            + "un-bill the Delivery Receipt. Fails if already voided or if any payment/credit is still applied to it (void those first).")
    @ApiResponse(responseCode = "200", description = "Invoice voided")
    @ApiResponse(responseCode = "400", description = "Already voided, or payments still applied")
    @ApiResponse(responseCode = "403", description = "Missing VOID_SALES_INVOICE permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_SALES_INVOICE')")
    public ResponseEntity<AppResponse<SalesInvoiceResponse>> voidInvoice(@Parameter(description = "Invoice ID") @PathVariable Long id,
                                                                         @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(salesInvoiceService.voidSalesInvoice(id, userDetails.getUsername()))));
    }

    private SalesInvoiceResponse toResponse(SalesInvoice invoice) {
        SalesInvoiceResponse res = new SalesInvoiceResponse();
        res.setId(invoice.getId());
        res.setCompanyId(invoice.getCompany().getId());
        res.setCompanyName(invoice.getCompany().getName());
        res.setCustomerId(invoice.getCustomer().getId());
        res.setCustomerName(invoice.getCustomer().getName());
        res.setWarehouseId(invoice.getWarehouse().getId());
        res.setWarehouseName(invoice.getWarehouse().getName());
        if (invoice.getDeliveryReceipt() != null) {
            res.setDeliveryReceiptId(invoice.getDeliveryReceipt().getId());
            res.setDeliveryReceiptReferenceNumber(invoice.getDeliveryReceipt().getReferenceNumber());
        }
        res.setReferenceNumber(invoice.getReferenceNumber());
        res.setSheetNumber(invoice.getSheetNumber());
        res.setInvoiceDate(invoice.getInvoiceDate());
        res.setTermDays(invoice.getTermDays());
        res.setDueDate(invoice.getDueDate());
        res.setRemarks(invoice.getRemarks());
        res.setDiscountPercentage(invoice.getDiscountPercentage());
        res.setNetAmount(invoice.getNetAmount());
        res.setPaymentStatus(invoice.getPaymentStatus());
        OpenItem item = invoice.getOpenItem();
        if (item != null) {
            res.setOpenItemId(item.getId());
            res.setAppliedAmount(item.getAppliedAmount());
            res.setPendingAmount(item.getPendingAmount());
            res.setBouncedAmount(item.getBouncedAmount());
            res.setBalance(item.getBalance());
        }
        res.setCreatedAt(invoice.getCreatedAt());
        res.setCreatedBy(invoice.getCreatedBy());
        res.setVoided(invoice.isVoided());
        res.setVoidedAt(invoice.getVoidedAt());
        res.setVoidedBy(invoice.getVoidedBy());
        res.setOrigin(invoice.getOrigin());
        List<SalesInvoiceLineResponse> lines = invoice.getLines().stream().map(this::toLineResponse).toList();
        res.setLines(lines);
        BigDecimal itemsSubtotal = lines.stream().map(SalesInvoiceLineResponse::getLineNet).reduce(BigDecimal.ZERO, BigDecimal::add);
        res.setItemsSubtotal(itemsSubtotal);
        res.setDiscountAmount(AccountingAmounts.discount(itemsSubtotal, invoice.getDiscountPercentage()));
        return res;
    }

    private SalesInvoiceLineResponse toLineResponse(SalesInvoiceLine line) {
        SalesInvoiceLineResponse res = new SalesInvoiceLineResponse();
        res.setId(line.getId());
        res.setItemId(line.getItem().getId());
        res.setItemCode(line.getItem().getItemCode());
        res.setItemName(line.getItem().getName());
        if (line.getDeliveryReceiptLine() != null) res.setDeliveryReceiptLineId(line.getDeliveryReceiptLine().getId());
        res.setQuantity(line.getQuantity());
        res.setUnitPrice(line.getUnitPrice());
        res.setDiscountPercentage(line.getDiscountPercentage());
        res.setLineNet(AccountingAmounts.lineNet(line.getQuantity(), line.getUnitPrice(), line.getDiscountPercentage()));
        res.setLineNumber(line.getLineNumber());
        return res;
    }
}
