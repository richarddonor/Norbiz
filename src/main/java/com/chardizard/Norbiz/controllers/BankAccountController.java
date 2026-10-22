package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.cache.CacheRegion;
import com.chardizard.Norbiz.cache.CacheScopeResolver;
import com.chardizard.Norbiz.cache.QueryCache;
import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.BankAccountRequest;
import com.chardizard.Norbiz.dto.BankAccountResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.BankAccount;
import com.chardizard.Norbiz.services.BankAccountService;
import com.chardizard.Norbiz.util.DateRangeUtils;
import com.chardizard.Norbiz.util.ListSort;
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

@Tag(name = "Bank Accounts", description = "The company's own bank accounts (deposit destinations, check sources, reconciled by Bank Reconciliation) — "
        + "requires VIEW_BANK_ACCOUNT / CREATE_BANK_ACCOUNT / UPDATE_BANK_ACCOUNT / DELETE_BANK_ACCOUNT permissions.")
@RestController
@RequestMapping("/bank-accounts")
@RequiredArgsConstructor
public class BankAccountController {

    // Column key → entity path for ?sort= (ListSort); anything else is a 400.
    private static final Map<String, String> SORTABLE = ListSort.fields(
            "company", "company.name",
            "bank", "bank.name",
            "name", "name",
            "accountNumber", "accountNumber",
            "active", "active",
            "createdBy", "createdBy",
            "updatedAt", "updatedAt");

    private final BankAccountService bankAccountService;
    private final QueryCache queryCache;
    private final CacheScopeResolver cacheScopes;

    @Operation(summary = "List bank accounts", description = "Returns bank accounts belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Bank account list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_BANK_ACCOUNT permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_BANK_ACCOUNT')")
    public ResponseEntity<AppResponse<PageResponse<BankAccountResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by bank ID") @RequestParam(required = false) Long bankId,
            @Parameter(description = "Filter by name (contains)") @RequestParam(required = false) String name,
            @Parameter(description = "Filter by account number (contains)") @RequestParam(required = false) String accountNumber,
            @Parameter(description = "Filter by bank name (contains)") @RequestParam(required = false) String bank,
            @Parameter(description = "Filter by company name (contains)") @RequestParam(required = false) String company,
            @Parameter(description = "Filter by creator (contains)") @RequestParam(required = false) String createdBy,
            @Parameter(description = "Filter by active status (true or false)") @RequestParam(required = false) String active,
            @Parameter(description = "Filter by last-updated date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtFrom,
            @Parameter(description = "Filter by last-updated date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtTo,
            Pageable pageable) {
        Pageable sorted = ListSort.apply(pageable, SORTABLE, ListSort.UPDATED_FIRST);
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(name)) filters.put("name", name);
        if (StringUtils.hasText(accountNumber)) filters.put("accountNumber", accountNumber);
        if (StringUtils.hasText(bank)) filters.put("bank", bank);
        if (StringUtils.hasText(company)) filters.put("company", company);
        if (StringUtils.hasText(createdBy)) filters.put("createdBy", createdBy);
        if (StringUtils.hasText(active)) filters.put("active", active);
        Instant fromInstant = DateRangeUtils.startOfDayUtc(updatedAtFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(updatedAtTo);

        var accounts = queryCache.page(CacheRegion.LIST_BANK_ACCOUNT, cacheScopes.forUser(userDetails.getUsername()),
                QueryCache.params("bankId", bankId, "filters", filters, "updatedAtFrom", fromInstant, "updatedAtTo", toInstant), sorted,
                BankAccountResponse.class,
                () -> bankAccountService.findAllForUser(userDetails.getUsername(), bankId, filters, fromInstant, toInstant, sorted).map(this::toResponse));
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(accounts)));
    }

    @Operation(summary = "Get bank account by ID")
    @ApiResponse(responseCode = "200", description = "Bank account returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_BANK_ACCOUNT permission or no access to company")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_BANK_ACCOUNT')")
    public ResponseEntity<AppResponse<BankAccountResponse>> getById(@Parameter(description = "Bank account ID") @PathVariable Long id,
                                                                    @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(bankAccountService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Create bank account", description = "Account number must be unique per company; the bank must be active.")
    @ApiResponse(responseCode = "201", description = "Bank account created")
    @ApiResponse(responseCode = "400", description = "Validation error, duplicate account number, inactive bank")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_BANK_ACCOUNT permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_BANK_ACCOUNT')")
    public ResponseEntity<AppResponse<BankAccountResponse>> create(@Valid @RequestBody BankAccountRequest request,
                                                                   @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(bankAccountService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Update bank account")
    @ApiResponse(responseCode = "200", description = "Bank account updated")
    @ApiResponse(responseCode = "400", description = "Validation error, duplicate account number, inactive bank")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_BANK_ACCOUNT permission or no access to company")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_BANK_ACCOUNT')")
    public ResponseEntity<AppResponse<BankAccountResponse>> update(@Parameter(description = "Bank account ID") @PathVariable Long id,
                                                                   @Valid @RequestBody BankAccountRequest request,
                                                                   @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(bankAccountService.update(id, request, userDetails.getUsername()))));
    }

    @Operation(summary = "Delete bank account")
    @ApiResponse(responseCode = "204", description = "Bank account deleted")
    @ApiResponse(responseCode = "403", description = "Missing DELETE_BANK_ACCOUNT permission or no access to company")
    @ApiResponse(responseCode = "409", description = "Still used by other records (code ENTITY_IN_USE)")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE_BANK_ACCOUNT')")
    public ResponseEntity<Void> delete(@Parameter(description = "Bank account ID") @PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        bankAccountService.delete(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    private BankAccountResponse toResponse(BankAccount account) {
        BankAccountResponse res = new BankAccountResponse();
        res.setId(account.getId());
        res.setCompanyId(account.getCompany().getId());
        res.setCompanyName(account.getCompany().getName());
        res.setBankId(account.getBank().getId());
        res.setBankCode(account.getBank().getCode());
        res.setBankName(account.getBank().getName());
        res.setName(account.getName());
        res.setAccountNumber(account.getAccountNumber());
        res.setOpeningBalance(account.getOpeningBalance());
        res.setOpeningBalanceDate(account.getOpeningBalanceDate());
        res.setActive(account.isActive());
        res.setCreatedAt(account.getCreatedAt());
        res.setUpdatedAt(account.getUpdatedAt());
        res.setCreatedBy(account.getCreatedBy());
        res.setUpdatedBy(account.getUpdatedBy());
        return res;
    }
}
