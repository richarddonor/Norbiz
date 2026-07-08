package com.chardizard.Norbiz.models;

import com.chardizard.Norbiz.audit.AuditAction;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Immutable append-only record of a change to any Auditable entity.
 * Intentionally does NOT extend Auditable to avoid recursive audit logging.
 */
@Getter
@Setter
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Simple class name of the changed entity (e.g. "Item", "Brand"). */
    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    /** Primary key of the changed entity. */
    @Column(name = "entity_id")
    private Long entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuditAction action;

    /** Username of the user who triggered the change. */
    @Column(name = "changed_by", length = 100)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    /**
     * JSON payload describing the change.
     * CREATE / DELETE: full field snapshot — e.g. {"name":"Acme","active":"true"}
     * UPDATE: array of changed fields — e.g. [{"field":"name","oldValue":"X","newValue":"Y"}]
     */
    @Column(columnDefinition = "text")
    private String changes;
}
