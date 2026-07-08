package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.audit.AuditAction;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class AuditLogResponse {
    private Long id;
    private String entityType;
    private Long entityId;
    private AuditAction action;
    private String changedBy;
    private Instant changedAt;
    private String changes;
}
