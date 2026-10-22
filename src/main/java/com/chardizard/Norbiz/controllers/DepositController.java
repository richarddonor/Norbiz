package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.Deposit;
import com.chardizard.Norbiz.models.DepositLine;
import com.chardizard.Norbiz.models.TransactionOrigin;
import com.chardizard.Norbiz.services.DepositService;
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

@Tag(name = "Deposits", description = "Deposit slips taking received cash and checks to one of our bank accounts — requires VIEW_DEPOSIT / CREATE_DEPOSIT / "
        + "VOID_DEPOSIT. Checks become DEPOSITED (cleared later by Bank Reconciliation or by hand); cash clears at once.")
@RestController
@RequestMapping("/deposits")
@RequiredArgsConstructor
public class DepositController {

    // Column key → entity path for ?sort= (ListSort); anything else is a 400.
    private static final Map<String, String> SORTABLE = ListSort.fields(
            "company", "company.name",
            "referenceNumber", "referenceNumber",
            "sheetNumber", "sheetNumber",
            "voided", "voided",
            "bankAccount", "bankAccount.name",
            "date", "depositDate",
            "totalAmount", "totalAmount");

    private final DepositService depositService;

    @Operation(summary = "List deposits", description = "Returns deposits belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "List returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_DEPOSIT permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_DEPOSIT')")
    public ResponseEntity<AppResponse<PageResponse<DepositResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by bank account ID") @RequestParam(required = false) Long bankAccountId,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by deposit slip number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by any deposited check number (contains)") @RequestParam(required = false) String checkNumber,
            @Parameter(description = "Filter by remarks (contains)") @RequestParam(required = false) String remarks,
            @Parameter(description = "Filter by voided status (true or false)") @RequestParam(required = false) String voided,
            @Parameter(description = "Filter by deposit date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by deposit date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            @Parameter(description = "Filter by origin") @RequestParam(required = false) TransactionOrigin origin,
            @Parameter(description = ListTotals.PARAM) @RequestParam(defaultValue = "false") boolean totals,
            Pageable pageable) {
        Pageable sorted = ListSort.apply(pageable, SORTABLE, ListSort.CREATED_FIRST);
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);
        if (StringUtils.hasText(checkNumber)) filters.put("checkNumber", checkNumber);
        if (StringUtils.hasText(remarks)) filters.put("remarks", remarks);
        if (StringUtils.hasText(voided)) filters.put("voided", voided);
        if (origin != null) filters.put("origin", origin.name());
        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var page = depositService.findAllForUser(userDetails.getUsername(), bankAccountId, filters, fromInstant, toInstant, sorted)
                .map(d -> toResponse(d, false));
        var response = PageResponse.of(page);
        if (totals) {
            response.withTotals(ListTotals.within(page.getTotalElements(), () -> depositService.findAllForUser(userDetails.getUsername(),
                            bankAccountId, filters, fromInstant, toInstant, ListSort.unpaged(sorted)).map(d -> toResponse(d, false)).getContent(),
                    ListTotals.column("totalAmount", DepositResponse::getTotalAmount)));
        }
        return ResponseEntity.ok(AppResponse.of(response));
    }

    @Operation(summary = "Get deposit by ID", description = "Includes the deposited tenders with their current status.")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_DEPOSIT permission or no access to company")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_DEPOSIT')")
    public ResponseEntity<AppResponse<DepositResponse>> getById(@Parameter(description = "Deposit ID") @PathVariable Long id,
                                                                @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(depositService.findById(id, userDetails.getUsername()), true)));
    }

    @Operation(summary = "Post deposit", description = "Every tender must be a cash or check tender of a customer payment that is ON_HAND, received "
            + "on/before the deposit date, and (checks) not post-dated past it. Posts one DEPOSIT entry to the account's book. Numbered DEP-.")
    @ApiResponse(responseCode = "201", description = "Posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. tender not ON_HAND, post-dated check, inactive account)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_DEPOSIT permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_DEPOSIT')")
    public ResponseEntity<AppResponse<DepositResponse>> create(@Valid @RequestBody DepositRequest request,
                                                               @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED).body(AppResponse.of(toResponse(depositService.create(request, userDetails.getUsername()), true)));
    }

    @Operation(summary = "Void deposit", description = "Puts its cash and checks back ON_HAND. Fails once reconciled or once any of its checks cleared or bounced.")
    @ApiResponse(responseCode = "200", description = "Voided")
    @ApiResponse(responseCode = "400", description = "Already voided, reconciled, or a check cleared/bounced")
    @ApiResponse(responseCode = "403", description = "Missing VOID_DEPOSIT permission or no access to company")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_DEPOSIT')")
    public ResponseEntity<AppResponse<DepositResponse>> voidDeposit(@Parameter(description = "Deposit ID") @PathVariable Long id,
                                                                    @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(depositService.voidDeposit(id, userDetails.getUsername()), true)));
    }

    private DepositResponse toResponse(Deposit deposit, boolean detail) {
        DepositResponse res = new DepositResponse();
        res.setId(deposit.getId());
        res.setCompanyId(deposit.getCompany().getId());
        res.setCompanyName(deposit.getCompany().getName());
        res.setBankAccountId(deposit.getBankAccount().getId());
        res.setBankAccountName(deposit.getBankAccount().getName());
        res.setBankAccountNumber(deposit.getBankAccount().getAccountNumber());
        res.setReferenceNumber(deposit.getReferenceNumber());
        res.setSheetNumber(deposit.getSheetNumber());
        res.setDepositDate(deposit.getDepositDate());
        res.setRemarks(deposit.getRemarks());
        res.setTotalAmount(deposit.getTotalAmount());
        res.setCreatedAt(deposit.getCreatedAt());
        res.setCreatedBy(deposit.getCreatedBy());
        res.setVoided(deposit.isVoided());
        res.setVoidedAt(deposit.getVoidedAt());
        res.setVoidedBy(deposit.getVoidedBy());
        res.setOrigin(deposit.getOrigin());
        if (detail) {
            res.setReconciled(depositService.isReconciled(deposit));
            res.setLines(deposit.getLines().stream().map(DepositLine::getTender).map(AccountingResponses::receivedTender).toList());
        }
        return res;
    }
}
