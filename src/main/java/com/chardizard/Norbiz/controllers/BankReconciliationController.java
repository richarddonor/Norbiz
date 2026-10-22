package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.BankReconciliation;
import com.chardizard.Norbiz.models.TransactionOrigin;
import com.chardizard.Norbiz.services.BankReconciliationService;
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

@Tag(name = "Bank Reconciliations", description = "Reconciles a bank account's book to a bank statement — requires VIEW_BANK_RECONCILIATION / "
        + "CREATE_BANK_RECONCILIATION / VOID_BANK_RECONCILIATION. Clearing a deposit clears its checks; clearing one of our check "
        + "disbursements clears that check. Only an account's latest reconciliation can be voided.")
@RestController
@RequestMapping("/bank-reconciliations")
@RequiredArgsConstructor
@Validated
public class BankReconciliationController {

    // Column key → entity path for ?sort= (ListSort); anything else is a 400.
    private static final Map<String, String> SORTABLE = ListSort.fields(
            "company", "company.name",
            "referenceNumber", "referenceNumber",
            "sheetNumber", "sheetNumber",
            "voided", "voided",
            "bankAccount", "bankAccount.name",
            "date", "statementDate",
            "statementEndingBalance", "statementEndingBalance");

    private final BankReconciliationService reconciliationService;

    @Operation(summary = "List bank reconciliations", description = "Returns reconciliations belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "List returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_BANK_RECONCILIATION permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_BANK_RECONCILIATION')")
    public ResponseEntity<AppResponse<PageResponse<BankReconciliationResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by bank account ID") @RequestParam(required = false) Long bankAccountId,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by bank account name (contains)") @RequestParam(required = false) String bankAccount,
            @Parameter(description = "Filter by voided status (true or false)") @RequestParam(required = false) String voided,
            @Parameter(description = "Filter by statement date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by statement date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            @Parameter(description = "Filter by origin") @RequestParam(required = false) TransactionOrigin origin,
            Pageable pageable) {
        Pageable sorted = ListSort.apply(pageable, SORTABLE, ListSort.CREATED_FIRST);
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);
        if (StringUtils.hasText(bankAccount)) filters.put("bankAccount", bankAccount);
        if (StringUtils.hasText(voided)) filters.put("voided", voided);
        if (origin != null) filters.put("origin", origin.name());
        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(reconciliationService.findAllForUser(userDetails.getUsername(), bankAccountId,
                filters, fromInstant, toInstant, sorted).map(r -> toResponse(r, false)))));
    }

    @Operation(summary = "Reconciliation starting point", description = "For a new reconciliation of the account as of statementDate: the previous "
            + "ending balance, the book balance, and every uncleared book entry up to that date to tick against the statement.")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_BANK_RECONCILIATION permission or no access to company")
    @GetMapping("/candidates")
    @PreAuthorize("hasAuthority('CREATE_BANK_RECONCILIATION')")
    public ResponseEntity<AppResponse<BankReconciliationCandidatesResponse>> candidates(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Bank account ID") @RequestParam Long bankAccountId,
            @Parameter(description = "Statement date (yyyy-MM-dd)") @RequestParam @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$") String statementDate) {
        var c = reconciliationService.candidates(bankAccountId, statementDate, userDetails.getUsername());
        BankReconciliationCandidatesResponse res = new BankReconciliationCandidatesResponse();
        res.setBankAccountId(c.account().getId());
        res.setStatementDate(c.statementDate());
        res.setPreviousBalance(c.previousBalance());
        if (c.previous() != null) {
            res.setPreviousReconciliationId(c.previous().getId());
            res.setPreviousReconciliationReferenceNumber(c.previous().getReferenceNumber());
            res.setPreviousStatementDate(c.previous().getStatementDate());
        }
        res.setBookBalance(c.bookBalance());
        res.setEntries(c.entries().stream().map(AccountingResponses::bankEntry).toList());
        return ResponseEntity.ok(AppResponse.of(res));
    }

    @Operation(summary = "Get bank reconciliation by ID")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_BANK_RECONCILIATION permission or no access to company")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_BANK_RECONCILIATION')")
    public ResponseEntity<AppResponse<BankReconciliationResponse>> getById(@Parameter(description = "Reconciliation ID") @PathVariable Long id,
                                                                           @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(reconciliationService.findById(id, userDetails.getUsername()), true)));
    }

    @Operation(summary = "Post bank reconciliation", description = "previous balance + cleared entries + adjustments must equal statementEndingBalance "
            + "(the error states the difference). The statement date must be after the account's previous reconciliation. Adjustments (bank "
            + "charges, interest...) are booked to the account and reconciled at once. Numbered BRC-.")
    @ApiResponse(responseCode = "201", description = "Posted")
    @ApiResponse(responseCode = "400", description = "Not balanced, entry already reconciled/voided/after the statement, or date not after the previous one")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_BANK_RECONCILIATION permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_BANK_RECONCILIATION')")
    public ResponseEntity<AppResponse<BankReconciliationResponse>> create(@Valid @RequestBody BankReconciliationRequest request,
                                                                          @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(reconciliationService.create(request, userDetails.getUsername()), true)));
    }

    @Operation(summary = "Void bank reconciliation", description = "Only the account's latest one: un-clears its entries, voids its adjustments and puts "
            + "the checks it cleared back to DEPOSITED/ISSUED.")
    @ApiResponse(responseCode = "200", description = "Voided")
    @ApiResponse(responseCode = "400", description = "Already voided, or not the latest")
    @ApiResponse(responseCode = "403", description = "Missing VOID_BANK_RECONCILIATION permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_BANK_RECONCILIATION')")
    public ResponseEntity<AppResponse<BankReconciliationResponse>> voidReconciliation(@Parameter(description = "Reconciliation ID") @PathVariable Long id,
                                                                                      @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(reconciliationService.voidReconciliation(id, userDetails.getUsername()), true)));
    }

    private BankReconciliationResponse toResponse(BankReconciliation r, boolean withLines) {
        BankReconciliationResponse res = new BankReconciliationResponse();
        res.setId(r.getId());
        res.setCompanyId(r.getCompany().getId());
        res.setCompanyName(r.getCompany().getName());
        res.setBankAccountId(r.getBankAccount().getId());
        res.setBankAccountName(r.getBankAccount().getName());
        res.setBankAccountNumber(r.getBankAccount().getAccountNumber());
        res.setReferenceNumber(r.getReferenceNumber());
        res.setSheetNumber(r.getSheetNumber());
        res.setStatementDate(r.getStatementDate());
        res.setRemarks(r.getRemarks());
        res.setPreviousBalance(r.getPreviousBalance());
        res.setStatementEndingBalance(r.getStatementEndingBalance());
        res.setClearedTotal(r.getClearedTotal());
        res.setAdjustmentsTotal(r.getAdjustmentsTotal());
        res.setBookBalance(r.getBookBalance());
        res.setOutstandingTotal(r.getOutstandingTotal());
        res.setCreatedAt(r.getCreatedAt());
        res.setCreatedBy(r.getCreatedBy());
        res.setVoided(r.isVoided());
        res.setVoidedAt(r.getVoidedAt());
        res.setVoidedBy(r.getVoidedBy());
        res.setOrigin(r.getOrigin());
        if (withLines) {
            res.setLines(r.getLines().stream().map(l -> {
                BankReconciliationLineResponse line = new BankReconciliationLineResponse();
                line.setId(l.getId());
                line.setKind(l.getKind());
                line.setBankEntryId(l.getBankEntry().getId());
                line.setAdjustmentType(l.getAdjustmentType());
                line.setEntryDate(l.getEntryDate());
                line.setReferenceNumber(l.getReferenceNumber());
                line.setCheckNumber(l.getCheckNumber());
                line.setDescription(l.getDescription());
                line.setAmount(l.getAmount());
                line.setLineNumber(l.getLineNumber());
                return line;
            }).toList());
        }
        return res;
    }
}
