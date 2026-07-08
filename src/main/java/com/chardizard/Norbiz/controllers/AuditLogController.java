package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.audit.AuditAction;
import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.AuditLogResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.AuditLog;
import com.chardizard.Norbiz.repositories.AuditLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Audit Logs", description = "Read-only access to the change history of all audited entities. Restricted to SUPER_ADMIN.")
@RestController
@RequestMapping("/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;

    @Operation(summary = "List all audit logs", description = "Returns every audit log entry, newest first.")
    @ApiResponse(responseCode = "200", description = "Audit logs returned")
    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<AppResponse<PageResponse<AuditLogResponse>>> getAll(
            @PageableDefault(sort = "changedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        var logs = auditLogRepository.findAll(pageable).map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(logs)));
    }

    @Operation(summary = "Get audit logs for a specific entity record")
    @ApiResponse(responseCode = "200", description = "Audit logs returned")
    @GetMapping("/{entityType}/{entityId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<AppResponse<PageResponse<AuditLogResponse>>> getByEntity(
            @Parameter(description = "Entity class name, e.g. Item, Brand") @PathVariable String entityType,
            @Parameter(description = "Entity primary key") @PathVariable Long entityId,
            Pageable pageable) {
        var logs = auditLogRepository
                .findByEntityTypeAndEntityIdOrderByChangedAtDesc(entityType, entityId, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(logs)));
    }

    @Operation(summary = "Get audit logs by entity type")
    @ApiResponse(responseCode = "200", description = "Audit logs returned")
    @GetMapping("/entity/{entityType}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<AppResponse<PageResponse<AuditLogResponse>>> getByEntityType(
            @Parameter(description = "Entity class name, e.g. Item, Brand") @PathVariable String entityType,
            Pageable pageable) {
        var logs = auditLogRepository
                .findByEntityTypeOrderByChangedAtDesc(entityType, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(logs)));
    }

    @Operation(summary = "Get audit logs by user")
    @ApiResponse(responseCode = "200", description = "Audit logs returned")
    @GetMapping("/user/{username}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<AppResponse<PageResponse<AuditLogResponse>>> getByUser(
            @Parameter(description = "Username who made the change") @PathVariable String username,
            Pageable pageable) {
        var logs = auditLogRepository
                .findByChangedByOrderByChangedAtDesc(username, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(logs)));
    }

    @Operation(summary = "Get audit logs by action type")
    @ApiResponse(responseCode = "200", description = "Audit logs returned")
    @GetMapping("/action/{action}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<AppResponse<PageResponse<AuditLogResponse>>> getByAction(
            @Parameter(description = "Action: CREATE, UPDATE, or DELETE") @PathVariable AuditAction action,
            Pageable pageable) {
        var logs = auditLogRepository
                .findByActionOrderByChangedAtDesc(action, pageable)
                .map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(logs)));
    }

    private AuditLogResponse toResponse(AuditLog log) {
        AuditLogResponse res = new AuditLogResponse();
        res.setId(log.getId());
        res.setEntityType(log.getEntityType());
        res.setEntityId(log.getEntityId());
        res.setAction(log.getAction());
        res.setChangedBy(log.getChangedBy());
        res.setChangedAt(log.getChangedAt());
        res.setChanges(log.getChanges());
        return res;
    }
}