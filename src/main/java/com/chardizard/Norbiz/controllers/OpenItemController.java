package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.services.OpenItemQueryService;
import com.chardizard.Norbiz.util.DateRangeUtils;
import com.chardizard.Norbiz.util.ListSort;
import com.chardizard.Norbiz.util.ListTotals;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Open Items", description = "The AR/AP subledger's receivables, payables and credits with their balances — VIEW_AR_REPORT for "
        + "side=RECEIVABLE, VIEW_AP_REPORT for side=PAYABLE.")
@RestController
@RequestMapping("/open-items")
@RequiredArgsConstructor
public class OpenItemController {

    // Column key → entity path for ?sort= (ListSort); anything else is a 400.
    private static final Map<String, String> SORTABLE = ListSort.fields(
            "company", "company.name",
            "customer", "customer.name",
            "supplier", "supplier.name",
            "referenceNumber", "referenceNumber",
            "sourceType", "sourceType",
            "direction", "direction",
            "documentDate", "documentDate",
            "dueDate", "dueDate",
            "amount", "amount",
            "balance", "balance",
            "status", "status");

    private final OpenItemQueryService openItemQueryService;

    @Getter
    @Setter
    public static class OpenItemDetailResponse {
        private OpenItemResponse item;
        // Every application to/from it, including reversed ones.
        private List<AllocationResponse> allocations;
    }

    @Operation(summary = "List open items", description = "E.g. side=RECEIVABLE&status=OPEN&overdueAsOf=2026-10-22 for overdue receivables. Default "
            + "order: due date, then document date.")
    @ApiResponse(responseCode = "200", description = "List returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_AR_REPORT (receivables) / VIEW_AP_REPORT (payables)")
    @GetMapping
    @PreAuthorize("(#side.name() == 'RECEIVABLE' and hasAuthority('VIEW_AR_REPORT')) or (#side.name() == 'PAYABLE' and hasAuthority('VIEW_AP_REPORT'))")
    public ResponseEntity<AppResponse<PageResponse<OpenItemResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "RECEIVABLE (customers) or PAYABLE (suppliers)") @RequestParam AccountSide side,
            @Parameter(description = "Narrow to one company") @RequestParam(required = false) Long companyId,
            @Parameter(description = "Filter by customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by supplier ID") @RequestParam(required = false) Long supplierId,
            @Parameter(description = "CHARGE or CREDIT") @RequestParam(required = false) OpenItemDirection direction,
            @Parameter(description = "OPEN, CLOSED or VOIDED") @RequestParam(required = false) OpenItemStatus status,
            @Parameter(description = "Only OPEN charges due before this date (yyyy-MM-dd)") @RequestParam(required = false) String overdueAsOf,
            @Parameter(description = "Filter by source type") @RequestParam(required = false) OpenItemSourceType sourceType,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by customer name (contains)") @RequestParam(required = false) String customer,
            @Parameter(description = "Filter by supplier name (contains)") @RequestParam(required = false) String supplier,
            @Parameter(description = "Filter by document date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by document date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals,
            Pageable pageable) {
        Pageable sorted = ListSort.apply(pageable, SORTABLE, Sort.by("dueDate", "documentDate", "id"));
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(customer)) filters.put("customer", customer);
        if (StringUtils.hasText(supplier)) filters.put("supplier", supplier);
        if (sourceType != null) filters.put("sourceType", sourceType.name());
        Instant overdue = DateRangeUtils.startOfDayUtc(overdueAsOf);
        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var page = openItemQueryService.findAllForUser(userDetails.getUsername(), side, companyId, customerId, supplierId, direction, status,
                overdue, filters, fromInstant, toInstant, sorted).map(AccountingResponses::openItem);
        var response = PageResponse.of(page);
        if (totals) {
            response.withTotals(ListTotals.within(page.getTotalElements(), () -> openItemQueryService.findAllForUser(userDetails.getUsername(), side,
                            companyId, customerId, supplierId, direction, status, overdue, filters, fromInstant, toInstant, ListSort.unpaged(sorted))
                            .map(AccountingResponses::openItem).getContent(),
                    ListTotals.column("amount", OpenItemResponse::getAmount),
                    ListTotals.column("balance", OpenItemResponse::getBalance)));
        }
        return ResponseEntity.ok(AppResponse.of(response));
    }

    @Operation(summary = "Get open item by ID", description = "With every application to/from it (payment history), including reversed ones.")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_AR_REPORT/VIEW_AP_REPORT for its side, or no access to company")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('VIEW_AR_REPORT', 'VIEW_AP_REPORT')")
    public ResponseEntity<AppResponse<OpenItemDetailResponse>> getById(@Parameter(description = "Open item ID") @PathVariable Long id,
                                                                       @AuthenticationPrincipal UserDetails userDetails) {
        OpenItem item = openItemQueryService.findById(id, userDetails.getUsername());
        String needed = item.getSide() == AccountSide.RECEIVABLE ? "VIEW_AR_REPORT" : "VIEW_AP_REPORT";
        if (userDetails.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals(needed))) {
            throw new SecurityException("Missing " + needed);
        }
        OpenItemDetailResponse res = new OpenItemDetailResponse();
        res.setItem(AccountingResponses.openItem(item));
        res.setAllocations(openItemQueryService.allocationsTouching(item).stream().map(AccountingResponses::allocation).toList());
        return ResponseEntity.ok(AppResponse.of(res));
    }
}
