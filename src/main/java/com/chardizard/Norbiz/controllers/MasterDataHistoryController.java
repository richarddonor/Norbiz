package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.ChangeHistoryEntryResponse;
import com.chardizard.Norbiz.dto.DeletedRecordResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.MasterDataType;
import com.chardizard.Norbiz.services.MasterDataHistoryService;
import com.chardizard.Norbiz.util.DateRangeUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

// Authorization is enforced in MasterDataHistoryService: the type's VIEW_ permission + company
// access to the record — a static @PreAuthorize can't express a per-type permission.
@Tag(name = "Master Data Change History",
     description = "Field-level change history of master-data records, including deleted ones, from the audit log. Requires the record type's VIEW_ permission and access to the record's company.")
@RestController
@RequestMapping("/master-data/{type}")
@RequiredArgsConstructor
public class MasterDataHistoryController {

    private final MasterDataHistoryService masterDataHistoryService;

    @Operation(summary = "Get a record's change history",
               description = "Newest first. Works for deleted records too (their last entry is the DELETE). "
                       + "Audit bookkeeping fields (createdAt/By, updatedAt/By) are omitted from the changes.")
    @ApiResponse(responseCode = "200", description = "History returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ permission or no access to company")
    @ApiResponse(responseCode = "400", description = "Record not found or unknown type")
    @GetMapping("/{id}/history")
    public ResponseEntity<AppResponse<PageResponse<ChangeHistoryEntryResponse>>> getHistory(
            @Parameter(description = "Record type, e.g. ITEM, SUPPLIER") @PathVariable MasterDataType type,
            @Parameter(description = "Record ID") @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails,
            @PageableDefault(size = 50) Pageable pageable) {
        var history = masterDataHistoryService.findHistory(type, id, userDetails.getUsername(), pageable);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(history)));
    }

    @Operation(summary = "List deleted records of a type",
               description = "Newest deletion first, from the DELETE audit logs. Scoped to companyId if given, else every company "
                       + "the caller belongs to (all for SUPER_ADMIN; Users are not company-scoped). Open a record's history with "
                       + "GET /master-data/{type}/{id}/history. ITEM_SKU is not supported — a SKU's removal shows in its item's history.")
    @ApiResponse(responseCode = "200", description = "Deleted records returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ permission or no access to company")
    @ApiResponse(responseCode = "400", description = "Unknown or unsupported type")
    @GetMapping("/deleted")
    public ResponseEntity<AppResponse<PageResponse<DeletedRecordResponse>>> getDeleted(
            @Parameter(description = "Record type, e.g. ITEM, SUPPLIER") @PathVariable MasterDataType type,
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Only records of this company") @RequestParam(required = false) Long companyId,
            @Parameter(description = "Filter by code (contains)") @RequestParam(required = false) @Size(max = 255) String code,
            @Parameter(description = "Filter by name (contains)") @RequestParam(required = false) @Size(max = 255) String name,
            @Parameter(description = "Filter by who deleted it (contains)") @RequestParam(required = false) @Size(max = 255) String deletedBy,
            @Parameter(description = "Filter by deletion date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String deletedAtFrom,
            @Parameter(description = "Filter by deletion date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String deletedAtTo,
            @PageableDefault(size = 50) Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(code)) filters.put("code", code);
        if (StringUtils.hasText(name)) filters.put("name", name);
        if (StringUtils.hasText(deletedBy)) filters.put("deletedBy", deletedBy);

        Instant fromInstant = DateRangeUtils.startOfDayUtc(deletedAtFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(deletedAtTo);

        var deleted = masterDataHistoryService.findDeleted(type, companyId, filters, fromInstant, toInstant,
                userDetails.getUsername(), pageable);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(deleted)));
    }
}
