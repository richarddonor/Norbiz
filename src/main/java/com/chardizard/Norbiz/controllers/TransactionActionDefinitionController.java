package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.dto.TransactionActionDefinitionRequest;
import com.chardizard.Norbiz.dto.TransactionActionDefinitionResponse;
import com.chardizard.Norbiz.models.TransactionActionDefinition;
import com.chardizard.Norbiz.models.TransactionType;
import com.chardizard.Norbiz.services.TransactionActionDefinitionService;
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

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "Transaction Action Definitions",
     description = "Company-configured actions users can take on transactions (e.g. Purchase Receive: BARCODE_PRINTING) — requires MANAGE_TRANSACTION_ACTIONS.")
@RestController
@RequestMapping("/transaction-action-definitions")
@RequiredArgsConstructor
public class TransactionActionDefinitionController {

    private final TransactionActionDefinitionService definitionService;

    @Operation(summary = "List transaction action definitions")
    @ApiResponse(responseCode = "200", description = "Definition list returned")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE_TRANSACTION_ACTIONS permission")
    @GetMapping
    @PreAuthorize("hasAuthority('MANAGE_TRANSACTION_ACTIONS')")
    public ResponseEntity<AppResponse<PageResponse<TransactionActionDefinitionResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by transaction type") @RequestParam(required = false) TransactionType transactionType,
            @Parameter(description = "Filter by code (contains)") @RequestParam(required = false) String code,
            @Parameter(description = "Filter by name (contains)") @RequestParam(required = false) String name,
            @Parameter(description = "Filter by active flag") @RequestParam(required = false) Boolean active,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(code)) filters.put("code", code);
        if (StringUtils.hasText(name)) filters.put("name", name);

        var definitions = definitionService.findAllForUser(userDetails.getUsername(), transactionType, filters, active, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(definitions)));
    }

    @Operation(summary = "Get transaction action definition by ID")
    @ApiResponse(responseCode = "200", description = "Definition returned")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE_TRANSACTION_ACTIONS permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Definition not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MANAGE_TRANSACTION_ACTIONS')")
    public ResponseEntity<AppResponse<TransactionActionDefinitionResponse>> getById(
            @Parameter(description = "Definition ID") @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(definitionService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Create transaction action definition",
               description = "Code must be unique per company + transaction type. Prerequisites must be same company + type and acyclic.")
    @ApiResponse(responseCode = "201", description = "Definition created")
    @ApiResponse(responseCode = "400", description = "Validation failed (duplicate code, invalid role/prerequisite)")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE_TRANSACTION_ACTIONS permission")
    @PostMapping
    @PreAuthorize("hasAuthority('MANAGE_TRANSACTION_ACTIONS')")
    public ResponseEntity<AppResponse<TransactionActionDefinitionResponse>> create(
            @Valid @RequestBody TransactionActionDefinitionRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(definitionService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Update transaction action definition", description = "companyId and transactionType cannot be changed.")
    @ApiResponse(responseCode = "200", description = "Definition updated")
    @ApiResponse(responseCode = "400", description = "Validation failed (duplicate code, cycle, invalid role/prerequisite)")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE_TRANSACTION_ACTIONS permission")
    @ApiResponse(responseCode = "404", description = "Definition not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MANAGE_TRANSACTION_ACTIONS')")
    public ResponseEntity<AppResponse<TransactionActionDefinitionResponse>> update(
            @Parameter(description = "Definition ID") @PathVariable Long id,
            @Valid @RequestBody TransactionActionDefinitionRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(definitionService.update(id, request, userDetails.getUsername()))));
    }

    @Operation(summary = "Delete transaction action definition",
               description = "Blocked once the action has been taken on any transaction or is another action's prerequisite — deactivate instead.")
    @ApiResponse(responseCode = "204", description = "Definition deleted")
    @ApiResponse(responseCode = "400", description = "Definition is in use")
    @ApiResponse(responseCode = "403", description = "Missing MANAGE_TRANSACTION_ACTIONS permission")
    @ApiResponse(responseCode = "404", description = "Definition not found")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('MANAGE_TRANSACTION_ACTIONS')")
    public ResponseEntity<Void> delete(@Parameter(description = "Definition ID") @PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        definitionService.delete(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    private TransactionActionDefinitionResponse toResponse(TransactionActionDefinition definition) {
        TransactionActionDefinitionResponse res = new TransactionActionDefinitionResponse();
        res.setId(definition.getId());
        res.setCompanyId(definition.getCompany().getId());
        res.setCompanyName(definition.getCompany().getName());
        res.setTransactionType(definition.getTransactionType());
        res.setCode(definition.getCode());
        res.setName(definition.getName());
        res.setSortOrder(definition.getSortOrder());
        res.setActive(definition.isActive());
        res.setAllowedRoles(definition.getAllowedRoles().stream()
                .sorted(Comparator.comparing(r -> r.getName()))
                .map(r -> ref(r.getId(), r.getName(), r.getDisplayName()))
                .toList());
        res.setPrerequisites(definition.getPrerequisites().stream()
                .sorted(Comparator.comparingInt(TransactionActionDefinition::getSortOrder))
                .map(p -> ref(p.getId(), p.getCode(), p.getName()))
                .toList());
        res.setCreatedAt(definition.getCreatedAt());
        res.setUpdatedAt(definition.getUpdatedAt());
        res.setCreatedBy(definition.getCreatedBy());
        res.setUpdatedBy(definition.getUpdatedBy());
        return res;
    }

    private TransactionActionDefinitionResponse.Ref ref(Long id, String code, String name) {
        TransactionActionDefinitionResponse.Ref ref = new TransactionActionDefinitionResponse.Ref();
        ref.setId(id);
        ref.setCode(code);
        ref.setName(name);
        return ref;
    }
}
