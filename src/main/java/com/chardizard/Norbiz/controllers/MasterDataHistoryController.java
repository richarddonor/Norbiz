package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.ChangeHistoryEntryResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.MasterDataType;
import com.chardizard.Norbiz.services.MasterDataHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

// Authorization is enforced in MasterDataHistoryService: the type's VIEW_ permission + company
// access to the record — a static @PreAuthorize can't express a per-type permission.
@Tag(name = "Master Data Change History",
     description = "Field-level change history of one master-data record, from the audit log. Requires the record type's VIEW_ permission and access to the record's company.")
@RestController
@RequestMapping("/master-data/{type}/{id}")
@RequiredArgsConstructor
public class MasterDataHistoryController {

    private final MasterDataHistoryService masterDataHistoryService;

    @Operation(summary = "Get a record's change history",
               description = "Newest first. Audit bookkeeping fields (createdAt/By, updatedAt/By) are omitted from the changes.")
    @ApiResponse(responseCode = "200", description = "History returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ permission or no access to company")
    @ApiResponse(responseCode = "400", description = "Record not found or unknown type")
    @GetMapping("/history")
    public ResponseEntity<AppResponse<PageResponse<ChangeHistoryEntryResponse>>> getHistory(
            @Parameter(description = "Record type, e.g. ITEM, SUPPLIER") @PathVariable MasterDataType type,
            @Parameter(description = "Record ID") @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails,
            @PageableDefault(size = 50) Pageable pageable) {
        var history = masterDataHistoryService.findHistory(type, id, userDetails.getUsername(), pageable);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(history)));
    }
}
