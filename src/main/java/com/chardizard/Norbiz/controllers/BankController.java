package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.cache.CacheRegion;
import com.chardizard.Norbiz.cache.CacheScopeResolver;
import com.chardizard.Norbiz.cache.QueryCache;
import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.BankRequest;
import com.chardizard.Norbiz.dto.BankResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.Bank;
import com.chardizard.Norbiz.services.BankService;
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

@Tag(name = "Banks", description = "Banks (drawee banks of received checks, and the banks behind our own accounts) — requires VIEW_BANK / CREATE_BANK / UPDATE_BANK / DELETE_BANK permissions.")
@RestController
@RequestMapping("/banks")
@RequiredArgsConstructor
public class BankController {

    // Column key → entity path for ?sort= (ListSort); anything else is a 400.
    private static final Map<String, String> SORTABLE = ListSort.fields(
            "company", "company.name",
            "code", "code",
            "name", "name",
            "active", "active",
            "createdBy", "createdBy",
            "updatedAt", "updatedAt");

    private final BankService bankService;
    private final QueryCache queryCache;
    private final CacheScopeResolver cacheScopes;

    @Operation(summary = "List banks", description = "Returns banks belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Bank list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_BANK permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_BANK')")
    public ResponseEntity<AppResponse<PageResponse<BankResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by code (contains)") @RequestParam(required = false) String code,
            @Parameter(description = "Filter by name (contains)") @RequestParam(required = false) String name,
            @Parameter(description = "Filter by company name (contains)") @RequestParam(required = false) String company,
            @Parameter(description = "Filter by creator (contains)") @RequestParam(required = false) String createdBy,
            @Parameter(description = "Filter by active status (true or false)") @RequestParam(required = false) String active,
            @Parameter(description = "Filter by last-updated date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtFrom,
            @Parameter(description = "Filter by last-updated date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtTo,
            Pageable pageable) {
        Pageable sorted = ListSort.apply(pageable, SORTABLE, ListSort.UPDATED_FIRST);
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(code)) filters.put("code", code);
        if (StringUtils.hasText(name)) filters.put("name", name);
        if (StringUtils.hasText(company)) filters.put("company", company);
        if (StringUtils.hasText(createdBy)) filters.put("createdBy", createdBy);
        if (StringUtils.hasText(active)) filters.put("active", active);
        Instant fromInstant = DateRangeUtils.startOfDayUtc(updatedAtFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(updatedAtTo);

        var banks = queryCache.page(CacheRegion.LIST_BANK, cacheScopes.forUser(userDetails.getUsername()),
                QueryCache.params("filters", filters, "updatedAtFrom", fromInstant, "updatedAtTo", toInstant), sorted, BankResponse.class,
                () -> bankService.findAllForUser(userDetails.getUsername(), filters, fromInstant, toInstant, sorted).map(this::toResponse));
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(banks)));
    }

    @Operation(summary = "Get bank by ID")
    @ApiResponse(responseCode = "200", description = "Bank returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_BANK permission or no access to company")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_BANK')")
    public ResponseEntity<AppResponse<BankResponse>> getById(@Parameter(description = "Bank ID") @PathVariable Long id,
                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(bankService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Create bank", description = "Bank code must be unique per company.")
    @ApiResponse(responseCode = "201", description = "Bank created")
    @ApiResponse(responseCode = "400", description = "Validation error or duplicate code")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_BANK permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_BANK')")
    public ResponseEntity<AppResponse<BankResponse>> create(@Valid @RequestBody BankRequest request,
                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED).body(AppResponse.of(toResponse(bankService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Update bank")
    @ApiResponse(responseCode = "200", description = "Bank updated")
    @ApiResponse(responseCode = "400", description = "Validation error or duplicate code")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_BANK permission or no access to company")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_BANK')")
    public ResponseEntity<AppResponse<BankResponse>> update(@Parameter(description = "Bank ID") @PathVariable Long id,
                                                            @Valid @RequestBody BankRequest request,
                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(bankService.update(id, request, userDetails.getUsername()))));
    }

    @Operation(summary = "Delete bank")
    @ApiResponse(responseCode = "204", description = "Bank deleted")
    @ApiResponse(responseCode = "403", description = "Missing DELETE_BANK permission or no access to company")
    @ApiResponse(responseCode = "409", description = "Still used by other records (code ENTITY_IN_USE)")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE_BANK')")
    public ResponseEntity<Void> delete(@Parameter(description = "Bank ID") @PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        bankService.delete(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    private BankResponse toResponse(Bank bank) {
        BankResponse res = new BankResponse();
        res.setId(bank.getId());
        res.setCompanyId(bank.getCompany().getId());
        res.setCompanyName(bank.getCompany().getName());
        res.setCode(bank.getCode());
        res.setName(bank.getName());
        res.setActive(bank.isActive());
        res.setCreatedAt(bank.getCreatedAt());
        res.setUpdatedAt(bank.getUpdatedAt());
        res.setCreatedBy(bank.getCreatedBy());
        res.setUpdatedBy(bank.getUpdatedBy());
        return res;
    }
}
