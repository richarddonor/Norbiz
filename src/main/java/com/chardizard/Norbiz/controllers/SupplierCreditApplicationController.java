package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.services.SupplierCreditApplicationService;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "Supplier Credit Applications", description = "Applies supplier credits posted earlier (advances = unapplied payment tenders, supplier debit "
        + "memos) to open payables — requires VIEW_SUPPLIER_CREDIT_APPLICATION / CREATE_SUPPLIER_CREDIT_APPLICATION / VOID_SUPPLIER_CREDIT_APPLICATION.")
@RestController
@RequestMapping("/supplier-credit-applications")
@RequiredArgsConstructor
public class SupplierCreditApplicationController {

    // Column key → entity path for ?sort= (ListSort); anything else is a 400.
    private static final Map<String, String> SORTABLE = ListSort.fields(
            "company", "company.name",
            "referenceNumber", "referenceNumber",
            "sheetNumber", "sheetNumber",
            "voided", "voided",
            "supplier", "supplier.name",
            "date", "applicationDate",
            "totalAmount", "totalAmount");

    private final SupplierCreditApplicationService applicationService;
    private final OpenItemQueryService openItemQueryService;

    @Operation(summary = "List supplier credit applications", description = "Returns applications belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "List returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_SUPPLIER_CREDIT_APPLICATION permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_SUPPLIER_CREDIT_APPLICATION')")
    public ResponseEntity<AppResponse<PageResponse<CreditApplicationResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by supplier ID") @RequestParam(required = false) Long supplierId,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by supplier name (contains)") @RequestParam(required = false) String supplier,
            @Parameter(description = "Filter by remarks (contains)") @RequestParam(required = false) String remarks,
            @Parameter(description = "Filter by voided status (true or false)") @RequestParam(required = false) String voided,
            @Parameter(description = "Filter by application date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by application date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            @Parameter(description = "Filter by origin") @RequestParam(required = false) TransactionOrigin origin,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals,
            Pageable pageable) {
        Pageable sorted = ListSort.apply(pageable, SORTABLE, ListSort.CREATED_FIRST);
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);
        if (StringUtils.hasText(supplier)) filters.put("supplier", supplier);
        if (StringUtils.hasText(remarks)) filters.put("remarks", remarks);
        if (StringUtils.hasText(voided)) filters.put("voided", voided);
        if (origin != null) filters.put("origin", origin.name());
        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var page = applicationService.findAllForUser(userDetails.getUsername(), supplierId, filters, fromInstant, toInstant, sorted)
                .map(a -> toResponse(a, false));
        var response = PageResponse.of(page);
        if (totals) {
            response.withTotals(ListTotals.within(page.getTotalElements(), () -> applicationService.findAllForUser(userDetails.getUsername(),
                            supplierId, filters, fromInstant, toInstant, ListSort.unpaged(sorted)).map(a -> toResponse(a, false)).getContent(),
                    ListTotals.column("totalAmount", CreditApplicationResponse::getTotalAmount)));
        }
        return ResponseEntity.ok(AppResponse.of(response));
    }

    @Operation(summary = "Get supplier credit application by ID")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_SUPPLIER_CREDIT_APPLICATION permission or no access to company")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_SUPPLIER_CREDIT_APPLICATION')")
    public ResponseEntity<AppResponse<CreditApplicationResponse>> getById(@Parameter(description = "Application ID") @PathVariable Long id,
                                                                          @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(applicationService.findById(id, userDetails.getUsername()), true)));
    }

    @Operation(summary = "Post supplier credit application", description = "Each line applies part of an open CREDIT item of the supplier (an "
            + "advance or supplier debit memo) to one of its open CHARGE items (Purchase Invoices, supplier credit memos; GET /lookups/open-items). Dated on/after the accounting start date. Numbered SCA-.")
    @ApiResponse(responseCode = "201", description = "Posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. over-application, items of another supplier, voided/closed items)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_SUPPLIER_CREDIT_APPLICATION permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_SUPPLIER_CREDIT_APPLICATION')")
    public ResponseEntity<AppResponse<CreditApplicationResponse>> create(@Valid @RequestBody SupplierCreditApplicationRequest request,
                                                                         @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(applicationService.create(request, userDetails.getUsername()), true)));
    }

    @Operation(summary = "Void supplier credit application", description = "Reverses its applications that are still active.")
    @ApiResponse(responseCode = "200", description = "Voided")
    @ApiResponse(responseCode = "400", description = "Already voided")
    @ApiResponse(responseCode = "403", description = "Missing VOID_SUPPLIER_CREDIT_APPLICATION permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_SUPPLIER_CREDIT_APPLICATION')")
    public ResponseEntity<AppResponse<CreditApplicationResponse>> voidApplication(@Parameter(description = "Application ID") @PathVariable Long id,
                                                                                  @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(applicationService.voidApplication(id, userDetails.getUsername()), true)));
    }

    private CreditApplicationResponse toResponse(SupplierCreditApplication application, boolean withApplications) {
        CreditApplicationResponse res = new CreditApplicationResponse();
        res.setId(application.getId());
        res.setCompanyId(application.getCompany().getId());
        res.setCompanyName(application.getCompany().getName());
        res.setSupplierId(application.getSupplier().getId());
        res.setSupplierName(application.getSupplier().getName());
        res.setReferenceNumber(application.getReferenceNumber());
        res.setSheetNumber(application.getSheetNumber());
        res.setApplicationDate(application.getApplicationDate());
        res.setRemarks(application.getRemarks());
        res.setTotalAmount(application.getTotalAmount());
        res.setCreatedAt(application.getCreatedAt());
        res.setCreatedBy(application.getCreatedBy());
        res.setVoided(application.isVoided());
        res.setVoidedAt(application.getVoidedAt());
        res.setVoidedBy(application.getVoidedBy());
        res.setOrigin(application.getOrigin());
        res.setLines(application.getLines().stream().map(l -> {
            CreditApplicationLineResponse line = new CreditApplicationLineResponse();
            line.setId(l.getId());
            line.setCreditItemId(l.getCreditItem().getId());
            line.setCreditSourceType(l.getCreditItem().getSourceType());
            line.setCreditReferenceNumber(l.getCreditItem().getReferenceNumber());
            line.setChargeItemId(l.getChargeItem().getId());
            line.setChargeSourceType(l.getChargeItem().getSourceType());
            line.setChargeReferenceNumber(l.getChargeItem().getReferenceNumber());
            line.setAmount(l.getAmount());
            line.setLineNumber(l.getLineNumber());
            return line;
        }).toList());
        if (withApplications) {
            res.setApplications(openItemQueryService.allocationsBySource(TransactionType.SUPPLIER_CREDIT_APPLICATION, application.getId()).stream()
                    .map(AccountingResponses::allocation).toList());
        }
        return res;
    }
}
