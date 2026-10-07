package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.services.AssemblyService;
import com.chardizard.Norbiz.util.DateRangeUtils;
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

@Tag(name = "Assemblies", description = "Assembly transactions — requires VIEW_ASSEMBLY / CREATE_ASSEMBLY / VOID_ASSEMBLY permissions. " +
        "Builds finished items in the main warehouse: outputs are added to on-hand, raw materials are deducted. " +
        "Immutable once posted: only create, view, and void.")
@RestController
@RequestMapping("/assemblies")
@RequiredArgsConstructor
public class AssemblyController {

    private final AssemblyService assemblyService;

    @Operation(summary = "List assemblies", description = "Returns assemblies belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "List returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ASSEMBLY permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_ASSEMBLY')")
    public ResponseEntity<AppResponse<PageResponse<AssemblyResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by reference number (contains)") @RequestParam(required = false) String referenceNumber,
            @Parameter(description = "Filter by sheet number (contains)") @RequestParam(required = false) String sheetNumber,
            @Parameter(description = "Filter by assembly date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateFrom,
            @Parameter(description = "Filter by assembly date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String dateTo,
            @Parameter(description = "Filter by origin: NATIVE, MIGRATED (copied from legacy) or RECONSTRUCTED (created by the migration)") @RequestParam(required = false) TransactionOrigin origin,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(referenceNumber)) filters.put("referenceNumber", referenceNumber);
        if (StringUtils.hasText(sheetNumber)) filters.put("sheetNumber", sheetNumber);
        if (origin != null) filters.put("origin", origin.name());

        Instant fromInstant = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(dateTo);

        var page = assemblyService.findAllForUser(userDetails.getUsername(), filters, fromInstant, toInstant, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(page)));
    }

    @Operation(summary = "Get Assembly by ID")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ASSEMBLY permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_ASSEMBLY')")
    public ResponseEntity<AppResponse<AssemblyResponse>> getById(@Parameter(description = "Assembly ID") @PathVariable Long id,
                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(assemblyService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Post Assembly", description = "Posts outputs (+on-hand) and consumed raw materials (-on-hand) in the main warehouse. Every item must be active and " +
            "INVENTORY-tagged; raw materials must be on hand. An output may name the bill of materials it was built from (it must produce that item).")
    @ApiResponse(responseCode = "201", description = "Posted")
    @ApiResponse(responseCode = "400", description = "Validation error (e.g. inactive item, bill of materials for another item, no main warehouse)")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_ASSEMBLY permission or no access to company")
    @ApiResponse(responseCode = "409", description = "Insufficient stock (code INSUFFICIENT_STOCK)")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_ASSEMBLY')")
    public ResponseEntity<AppResponse<AssemblyResponse>> create(@Valid @RequestBody AssemblyRequest request,
                                                            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(assemblyService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Void Assembly", description = "Cancels an assembly by reversing its ledger entries: outputs leave on-hand again and raw materials are returned. " +
            "Fails if already voided, or if the outputs have since been consumed.")
    @ApiResponse(responseCode = "200", description = "Voided")
    @ApiResponse(responseCode = "400", description = "Already voided")
    @ApiResponse(responseCode = "403", description = "Missing VOID_ASSEMBLY permission or no access to company")
    @ApiResponse(responseCode = "409", description = "Insufficient stock (code INSUFFICIENT_STOCK)")
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('VOID_ASSEMBLY')")
    public ResponseEntity<AppResponse<AssemblyResponse>> voidAssembly(@Parameter(description = "Assembly ID") @PathVariable Long id,
                                                             @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(assemblyService.voidAssembly(id, userDetails.getUsername()))));
    }

    private AssemblyResponse toResponse(Assembly assembly) {
        AssemblyResponse res = new AssemblyResponse();
        res.setId(assembly.getId());
        res.setCompanyId(assembly.getCompany().getId());
        res.setCompanyName(assembly.getCompany().getName());
        res.setWarehouseId(assembly.getWarehouse().getId());
        res.setWarehouseName(assembly.getWarehouse().getName());
        res.setReferenceNumber(assembly.getReferenceNumber());
        res.setSheetNumber(assembly.getSheetNumber());
        res.setAssemblyDate(assembly.getAssemblyDate());
        res.setRemarks(assembly.getRemarks());
        res.setCreatedAt(assembly.getCreatedAt());
        res.setCreatedBy(assembly.getCreatedBy());
        res.setVoided(assembly.isVoided());
        res.setVoidedAt(assembly.getVoidedAt());
        res.setVoidedBy(assembly.getVoidedBy());
        res.setLoaded(assembly.isLoaded());
        res.setOrigin(assembly.getOrigin());
        res.setOutputs(lines(assembly, AssemblyLineKind.OUTPUT));
        res.setMaterials(lines(assembly, AssemblyLineKind.MATERIAL));
        return res;
    }

    private List<AssemblyLineResponse> lines(Assembly assembly, AssemblyLineKind kind) {
        return assembly.getLines().stream().filter(l -> l.getKind() == kind).map(this::toLineResponse).toList();
    }

    private AssemblyLineResponse toLineResponse(AssemblyLine line) {
        AssemblyLineResponse res = new AssemblyLineResponse();
        res.setId(line.getId());
        res.setItemId(line.getItem().getId());
        res.setItemCode(line.getItem().getItemCode());
        res.setItemName(line.getItem().getName());
        res.setQuantity(line.getQuantity());
        res.setLineNumber(line.getLineNumber());
        if (line.getBillOfMaterial() != null) {
            res.setBillOfMaterialId(line.getBillOfMaterial().getId());
            res.setBillOfMaterialCode(line.getBillOfMaterial().getCode());
        }
        return res;
    }
}
