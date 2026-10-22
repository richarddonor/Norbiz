package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.services.SupplierPaymentService;
import com.chardizard.Norbiz.services.OpenItemQueryService;
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
import java.util.Set;
import java.util.stream.Collectors;

@Tag(name = "Supplier Payments", description = "Money paid to suppliers — requires VIEW_SUPPLIER_PAYMENT / CREATE_SUPPLIER_PAYMENT / VOID_SUPPLIER_PAYMENT. "
        + "One or more tenders (our checks, bank transfers, cash), optionally applied to the supplier's open payables (Purchase Invoices, "
        + "supplier credit memos); whatever is left unapplied is an advance to the supplier. Checks stay outstanding until cleared "
        + "(see Supplier Payment Tenders). Checked/approved sign-offs use Transaction Actions. Immutable once posted.")
@RestController
@RequestMapping("/supplier-payments")
@RequiredArgsConstructor
public class SupplierPaymentController {

    // Column key → entity path for ?sort= (ListSort); anything else is a 400.
    private static final Map<String, String> SORTABLE = ListSort.fields(
            "company", "company.name",
            "referenceNumber", "referenceNumber",
            "sheetNumber", "sheetNumber",
            "voided", "voided",
            "supplier", "supplier.name",
            "date", "paymentDate",
            "voucherNumber", "voucherNumber",
            "totalAmount", "totalAmount");

    private final SupplierPaymentService paymentService;
    private final OpenItemQueryService openItemQueryService;

    @Operation(summary = "List supplier payments", description = "Returns payments belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Payment list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_SUPPLIER_PAYMENT permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_SUPPLIER_PAYMENT')")
    public ResponseEntity<AppResponse<PageResponse<SupplierPaymentResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by supplier ID") @RequestParam(required = false) Long supplierId,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by supplier name (contains)") @RequestParam(required = false) String supplier,
            @Parameter(description = "Filter by any tender's check number (contains)") @RequestParam(required = false) String checkNumber,
            @Parameter(description = "Filter by voucher number (contains)") @RequestParam(required = false) String voucherNumber,
            @Parameter(description = "Filter by remarks (contains)") @RequestParam(required = false) String remarks,
            @Parameter(description = "Filter by voided status (true or false)") @RequestParam(required = false) String voided,
            @Parameter(description = "Filter by payment date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by payment date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            @Parameter(description = "Filter by origin") @RequestParam(required = false) TransactionOrigin origin,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals,
            Pageable pageable) {
        Pageable sorted = ListSort.apply(pageable, SORTABLE, ListSort.CREATED_FIRST);
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);
        if (StringUtils.hasText(supplier)) filters.put("supplier", supplier);
        if (StringUtils.hasText(checkNumber)) filters.put("checkNumber", checkNumber);
        if (StringUtils.hasText(voucherNumber)) filters.put("voucherNumber", voucherNumber);
        if (StringUtils.hasText(remarks)) filters.put("remarks", remarks);
        if (StringUtils.hasText(voided)) filters.put("voided", voided);
        if (origin != null) filters.put("origin", origin.name());
        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var payments = paymentService.findAllForUser(userDetails.getUsername(), supplierId, filters, fromInstant, toInstant, sorted);
        Map<Long, List<OpenItemAllocation>> allocations = openItemQueryService.allocationsBySources(TransactionType.SUPPLIER_PAYMENT,
                payments.getContent().stream().map(SupplierPayment::getId).toList());
        var page = payments.map(p -> toResponse(p, allocations.getOrDefault(p.getId(), List.of()), false));
        var response = PageResponse.of(page);
        if (totals) {
            response.withTotals(ListTotals.within(page.getTotalElements(), () -> paymentService.findAllForUser(userDetails.getUsername(),
                            supplierId, filters, fromInstant, toInstant, ListSort.unpaged(sorted)).map(p -> toResponse(p, null, false)).getContent(),
                    ListTotals.column("totalAmount", SupplierPaymentResponse::getTotalAmount),
                    ListTotals.column("unappliedAmount", SupplierPaymentResponse::getUnappliedAmount)));
        }
        return ResponseEntity.ok(AppResponse.of(response));
    }

    @Operation(summary = "Get supplier payment by ID", description = "Includes its tenders and the applications it made.")
    @ApiResponse(responseCode = "200", description = "Payment returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_SUPPLIER_PAYMENT permission or no access to company")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_SUPPLIER_PAYMENT')")
    public ResponseEntity<AppResponse<SupplierPaymentResponse>> getById(@Parameter(description = "Payment ID") @PathVariable Long id,
                                                                        @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(paymentService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Post supplier payment", description = "Tenders: CHECK (bankAccountId, checkNumber, checkDate required) is ISSUED "
            + "(outstanding) until it clears; BANK_TRANSFER (bankAccountId required) settles at once; CASH/OTHERS settle with no bank effect. "
            + "Checks and transfers post a disbursement to the bank account's book. replacesTenderId replaces a CANCELLED check. Applications "
            + "pay the supplier's open payables (GET /lookups/open-items), drawing from settled tenders first and then checks by check date "
            + "unless tenderIndex says otherwise; the rest stays as an advance. credits apply the supplier's existing credits (debit memos, "
            + "advances) to open payables in the same posting, before the tenders; a payment may use credits alone (no tenders). Dated on/after the accounting start date. Numbered SPY-.")
    @ApiResponse(responseCode = "201", description = "Payment posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. missing check details, over-application, inactive supplier)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_SUPPLIER_PAYMENT permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_SUPPLIER_PAYMENT')")
    public ResponseEntity<AppResponse<SupplierPaymentResponse>> create(@Valid @RequestBody SupplierPaymentRequest request,
                                                                       @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(paymentService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Void supplier payment", description = "Cancels a payment posted in error: reverses its applications and voids its bank "
            + "entries. Fails if any of those entries is already reconciled, or if a later Supplier Payment or Refund used its credit.")
    @ApiResponse(responseCode = "200", description = "Payment voided")
    @ApiResponse(responseCode = "400", description = "Already voided, reconciled, or used by later documents")
    @ApiResponse(responseCode = "403", description = "Missing VOID_SUPPLIER_PAYMENT permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_SUPPLIER_PAYMENT')")
    public ResponseEntity<AppResponse<SupplierPaymentResponse>> voidPayment(@Parameter(description = "Payment ID") @PathVariable Long id,
                                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(paymentService.voidPayment(id, userDetails.getUsername()))));
    }

    private SupplierPaymentResponse toResponse(SupplierPayment payment) {
        return toResponse(payment, openItemQueryService.allocationsBySource(TransactionType.SUPPLIER_PAYMENT, payment.getId()), true);
    }

    // allocations: the payment's own (null skips the paid/credits breakdown, e.g. for list totals).
    private SupplierPaymentResponse toResponse(SupplierPayment payment, List<OpenItemAllocation> allocations, boolean withApplications) {
        SupplierPaymentResponse res = new SupplierPaymentResponse();
        res.setId(payment.getId());
        res.setCompanyId(payment.getCompany().getId());
        res.setCompanyName(payment.getCompany().getName());
        res.setSupplierId(payment.getSupplier().getId());
        res.setSupplierName(payment.getSupplier().getName());
        res.setReferenceNumber(payment.getReferenceNumber());
        res.setVoucherNumber(payment.getVoucherNumber());
        res.setSheetNumber(payment.getSheetNumber());
        res.setPaymentDate(payment.getPaymentDate());
        res.setRemarks(payment.getRemarks());
        res.setTotalAmount(payment.getTotalAmount());
        res.setCreatedAt(payment.getCreatedAt());
        res.setCreatedBy(payment.getCreatedBy());
        res.setVoided(payment.isVoided());
        res.setVoidedAt(payment.getVoidedAt());
        res.setVoidedBy(payment.getVoidedBy());
        res.setOrigin(payment.getOrigin());
        List<IssuedTenderResponse> tenders = payment.getTenders().stream().map(AccountingResponses::issuedTender).toList();
        res.setTenders(tenders);
        res.setAppliedAmount(tenders.stream().map(IssuedTenderResponse::getAppliedAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        res.setUnappliedAmount(tenders.stream().map(IssuedTenderResponse::getUnappliedAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        if (allocations != null) {
            Set<Long> tenderItemIds = payment.getTenders().stream().filter(t -> t.getOpenItem() != null)
                    .map(t -> t.getOpenItem().getId()).collect(Collectors.toSet());
            AccountingResponses.PaymentBreakdown breakdown = AccountingResponses.paymentBreakdown(allocations, tenderItemIds, payment.isVoided());
            res.setPaidDocuments(breakdown.paidDocuments());
            res.setCreditsUsed(breakdown.creditsUsed());
            res.setCreditsUsedAmount(breakdown.creditsUsedAmount());
            if (withApplications) res.setApplications(allocations.stream().map(AccountingResponses::allocation).toList());
        }
        return res;
    }
}
