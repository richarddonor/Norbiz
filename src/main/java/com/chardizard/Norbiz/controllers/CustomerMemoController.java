package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.services.CustomerMemoService;
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

@Tag(name = "Customer Memos", description = "Customer debit/credit memos — requires VIEW_CUSTOMER_MEMO / CREATE_CUSTOMER_MEMO / VOID_CUSTOMER_MEMO permissions. "
        + "A DEBIT memo raises what the customer owes (a receivable); a CREDIT memo lowers it and can be applied to open charges. "
        + "OPENING_BALANCE memos carry balances from before the accounting start date. Immutable once posted: only create, view, and void.")
@RestController
@RequestMapping("/customer-memos")
@RequiredArgsConstructor
public class CustomerMemoController {

    // Column key → entity path for ?sort= (ListSort); anything else is a 400.
    private static final Map<String, String> SORTABLE = ListSort.fields(
            "company", "company.name",
            "referenceNumber", "referenceNumber",
            "sheetNumber", "sheetNumber",
            "voided", "voided",
            "customer", "customer.name",
            "memoType", "memoType",
            "reason", "reason",
            "date", "memoDate",
            "dueDate", "dueDate",
            "amount", "amount",
            "balance", "openItem.balance");

    private final CustomerMemoService memoService;
    private final OpenItemQueryService openItemQueryService;

    @Operation(summary = "List customer memos", description = "Returns memos belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Memo list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_CUSTOMER_MEMO permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_CUSTOMER_MEMO')")
    public ResponseEntity<AppResponse<PageResponse<MemoResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by memo type") @RequestParam(required = false) MemoType memoType,
            @Parameter(description = "Filter by reason") @RequestParam(required = false) CustomerMemoReason reason,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by customer name (contains)") @RequestParam(required = false) String customer,
            @Parameter(description = "Filter by remarks (contains)") @RequestParam(required = false) String remarks,
            @Parameter(description = "Filter by voided status (true or false)") @RequestParam(required = false) String voided,
            @Parameter(description = "Filter by memo date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by memo date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            @Parameter(description = "Filter by origin") @RequestParam(required = false) TransactionOrigin origin,
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

        var page = memoService.findAllForUser(userDetails.getUsername(), customerId, memoType, reason, filters, fromInstant, toInstant, sorted)
                .map(m -> toResponse(m, false));
        var response = PageResponse.of(page);
        if (totals) {
            response.withTotals(ListTotals.within(page.getTotalElements(), () -> memoService.findAllForUser(userDetails.getUsername(),
                            customerId, memoType, reason, filters, fromInstant, toInstant, ListSort.unpaged(sorted)).map(m -> toResponse(m, false)).getContent(),
                    ListTotals.column("amount", MemoResponse::getAmount),
                    ListTotals.column("balance", MemoResponse::getBalance)));
        }
        return ResponseEntity.ok(AppResponse.of(response));
    }

    @Operation(summary = "Get customer memo by ID", description = "Includes the applications the memo made.")
    @ApiResponse(responseCode = "200", description = "Memo returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_CUSTOMER_MEMO permission or no access to company")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_CUSTOMER_MEMO')")
    public ResponseEntity<AppResponse<MemoResponse>> getById(@Parameter(description = "Memo ID") @PathVariable Long id,
                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(memoService.findById(id, userDetails.getUsername()), true)));
    }

    @Operation(summary = "Post customer memo", description = "Lines (description + amount) make up the memo amount. A CREDIT memo may list applications "
            + "to the customer's open charges (GET /lookups/open-items). Dated on/after the accounting start date, except OPENING_BALANCE memos, "
            + "which must be dated before it (set dueDate to the original due date so aging is right). Numbered CDM-/CCM-.")
    @ApiResponse(responseCode = "201", description = "Memo posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. inactive customer, over-application, wrong date for the reason)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_CUSTOMER_MEMO permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_CUSTOMER_MEMO')")
    public ResponseEntity<AppResponse<MemoResponse>> create(@Valid @RequestBody CustomerMemoRequest request,
                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(memoService.create(request, userDetails.getUsername()), true)));
    }

    @Operation(summary = "Void customer memo", description = "Reverses the applications the memo made itself. Fails if already voided or while other "
            + "payments/applications are applied to/from it (void those first).")
    @ApiResponse(responseCode = "200", description = "Memo voided")
    @ApiResponse(responseCode = "400", description = "Already voided, or applied by other documents")
    @ApiResponse(responseCode = "403", description = "Missing VOID_CUSTOMER_MEMO permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_CUSTOMER_MEMO')")
    public ResponseEntity<AppResponse<MemoResponse>> voidMemo(@Parameter(description = "Memo ID") @PathVariable Long id,
                                                              @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(memoService.voidMemo(id, userDetails.getUsername()), true)));
    }

    private MemoResponse toResponse(CustomerMemo memo, boolean withApplications) {
        MemoResponse res = new MemoResponse();
        res.setId(memo.getId());
        res.setCompanyId(memo.getCompany().getId());
        res.setCompanyName(memo.getCompany().getName());
        res.setCustomerId(memo.getCustomer().getId());
        res.setCustomerName(memo.getCustomer().getName());
        res.setMemoType(memo.getMemoType());
        res.setReason(memo.getReason().name());
        res.setReferenceNumber(memo.getReferenceNumber());
        res.setSheetNumber(memo.getSheetNumber());
        res.setMemoDate(memo.getMemoDate());
        res.setTermDays(memo.getTermDays());
        res.setDueDate(memo.getDueDate());
        res.setOriginalReference(memo.getOriginalReference());
        res.setRemarks(memo.getRemarks());
        res.setAmount(memo.getAmount());
        OpenItem item = memo.getOpenItem();
        if (item != null) {
            res.setDirection(item.getDirection());
            res.setOpenItemId(item.getId());
            res.setAppliedAmount(item.getAppliedAmount());
            res.setBalance(item.getBalance());
        }
        res.setCreatedAt(memo.getCreatedAt());
        res.setCreatedBy(memo.getCreatedBy());
        res.setVoided(memo.isVoided());
        res.setVoidedAt(memo.getVoidedAt());
        res.setVoidedBy(memo.getVoidedBy());
        res.setOrigin(memo.getOrigin());
        res.setLines(memo.getLines().stream().map(l -> {
            MemoLineResponse line = new MemoLineResponse();
            line.setId(l.getId());
            line.setDescription(l.getDescription());
            line.setAmount(l.getAmount());
            line.setLineNumber(l.getLineNumber());
            return line;
        }).toList());
        if (withApplications) {
            res.setApplications(openItemQueryService.allocationsBySource(TransactionType.CUSTOMER_MEMO, memo.getId()).stream()
                    .map(AccountingResponses::allocation).toList());
        }
        return res;
    }
}
