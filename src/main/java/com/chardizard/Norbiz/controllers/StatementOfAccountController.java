package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.StatementOfAccount;
import com.chardizard.Norbiz.models.TransactionOrigin;
import com.chardizard.Norbiz.services.StatementOfAccountService;
import com.chardizard.Norbiz.util.DateRangeUtils;
import com.chardizard.Norbiz.util.ListSort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "Statements of Account", description = "A customer's open items as of a date, aged by due date and frozen for printing — requires "
        + "VIEW_STATEMENT_OF_ACCOUNT / CREATE_STATEMENT_OF_ACCOUNT / VOID_STATEMENT_OF_ACCOUNT. No ledger effect.")
@RestController
@RequestMapping("/statements-of-account")
@RequiredArgsConstructor
@Validated
public class StatementOfAccountController {

    // Column key → entity path for ?sort= (ListSort); anything else is a 400.
    private static final Map<String, String> SORTABLE = ListSort.fields(
            "company", "company.name",
            "referenceNumber", "referenceNumber",
            "sheetNumber", "sheetNumber",
            "voided", "voided",
            "customer", "customer.name",
            "date", "statementDate",
            "totalBalance", "totalBalance");

    private final StatementOfAccountService statementService;

    @Operation(summary = "List statements of account", description = "Returns statements belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "List returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_STATEMENT_OF_ACCOUNT permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_STATEMENT_OF_ACCOUNT')")
    public ResponseEntity<AppResponse<PageResponse<StatementOfAccountResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by customer ID") @RequestParam(required = false) Long customerId,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by customer name (contains)") @RequestParam(required = false) String customer,
            @Parameter(description = "Filter by voided status (true or false)") @RequestParam(required = false) String voided,
            @Parameter(description = "Filter by statement date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by statement date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            @Parameter(description = "Filter by origin") @RequestParam(required = false) TransactionOrigin origin,
            Pageable pageable) {
        Pageable sorted = ListSort.apply(pageable, SORTABLE, ListSort.CREATED_FIRST);
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);
        if (StringUtils.hasText(customer)) filters.put("customer", customer);
        if (StringUtils.hasText(voided)) filters.put("voided", voided);
        if (origin != null) filters.put("origin", origin.name());
        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(statementService.findAllForUser(userDetails.getUsername(), customerId, filters,
                fromInstant, toInstant, sorted).map(s -> toResponse(s, false)))));
    }

    @Operation(summary = "Preview a statement of account", description = "The statement as it would be issued now, without saving it.")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_STATEMENT_OF_ACCOUNT permission or no access to company")
    @GetMapping("/preview")
    @PreAuthorize("hasAuthority('VIEW_STATEMENT_OF_ACCOUNT')")
    public ResponseEntity<AppResponse<StatementOfAccountResponse>> preview(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Company ID") @RequestParam Long companyId,
            @Parameter(description = "Customer ID") @RequestParam Long customerId,
            @Parameter(description = "As-of date (yyyy-MM-dd)") @RequestParam @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$") String statementDate) {
        return ResponseEntity.ok(AppResponse.of(toResponse(statementService.preview(companyId, customerId, statementDate, userDetails.getUsername()), true)));
    }

    @Operation(summary = "Get statement of account by ID")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_STATEMENT_OF_ACCOUNT permission or no access to company")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_STATEMENT_OF_ACCOUNT')")
    public ResponseEntity<AppResponse<StatementOfAccountResponse>> getById(@Parameter(description = "Statement ID") @PathVariable Long id,
                                                                           @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(statementService.findById(id, userDetails.getUsername()), true)));
    }

    @Operation(summary = "Issue statement of account", description = "Freezes the customer's open items as of statementDate. Numbered SOA-.")
    @ApiResponse(responseCode = "201", description = "Issued")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_STATEMENT_OF_ACCOUNT permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_STATEMENT_OF_ACCOUNT')")
    public ResponseEntity<AppResponse<StatementOfAccountResponse>> create(@Valid @RequestBody StatementOfAccountRequest request,
                                                                          @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(statementService.create(request, userDetails.getUsername()), true)));
    }

    @Operation(summary = "Void statement of account")
    @ApiResponse(responseCode = "200", description = "Voided")
    @ApiResponse(responseCode = "400", description = "Already voided")
    @ApiResponse(responseCode = "403", description = "Missing VOID_STATEMENT_OF_ACCOUNT permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_STATEMENT_OF_ACCOUNT')")
    public ResponseEntity<AppResponse<StatementOfAccountResponse>> voidStatement(@Parameter(description = "Statement ID") @PathVariable Long id,
                                                                                 @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(statementService.voidStatement(id, userDetails.getUsername()), true)));
    }

    private StatementOfAccountResponse toResponse(StatementOfAccount s, boolean withLines) {
        StatementOfAccountResponse res = new StatementOfAccountResponse();
        res.setId(s.getId());
        res.setCompanyId(s.getCompany().getId());
        res.setCompanyName(s.getCompany().getName());
        res.setCustomerId(s.getCustomer().getId());
        res.setCustomerName(s.getCustomer().getName());
        res.setReferenceNumber(s.getReferenceNumber());
        res.setSheetNumber(s.getSheetNumber());
        res.setStatementDate(s.getStatementDate());
        res.setRemarks(s.getRemarks());
        res.setTotalBalance(s.getTotalBalance());
        res.setCurrentAmount(s.getCurrentAmount());
        res.setDays1To30(s.getDays1To30());
        res.setDays31To60(s.getDays31To60());
        res.setDays61To90(s.getDays61To90());
        res.setDays91To120(s.getDays91To120());
        res.setOver120(s.getOver120());
        res.setUnappliedCredits(s.getUnappliedCredits());
        res.setCreatedAt(s.getCreatedAt());
        res.setCreatedBy(s.getCreatedBy());
        res.setVoided(s.isVoided());
        res.setVoidedAt(s.getVoidedAt());
        res.setVoidedBy(s.getVoidedBy());
        res.setOrigin(s.getOrigin());
        if (withLines) {
            res.setLines(s.getLines().stream().map(l -> {
                StatementOfAccountLineResponse line = new StatementOfAccountLineResponse();
                line.setId(l.getId());
                line.setOpenItemId(l.getOpenItem().getId());
                line.setSourceType(l.getSourceType());
                line.setDirection(l.getDirection());
                line.setReferenceNumber(l.getReferenceNumber());
                line.setDocumentDate(l.getDocumentDate());
                line.setDueDate(l.getDueDate());
                line.setAmount(l.getAmount());
                line.setPaidAmount(l.getPaidAmount());
                line.setPendingAmount(l.getPendingAmount());
                line.setBalance(l.getBalance());
                line.setDaysOverdue(l.getDaysOverdue());
                line.setLineNumber(l.getLineNumber());
                return line;
            }).toList());
        }
        return res;
    }
}
