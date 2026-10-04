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
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_logs_entity", columnList = "entity_type, entity_id"),
        @Index(name = "idx_audit_logs_parent", columnList = "parent_type, parent_id")
})
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

    /** For entities that are part of another record (@AuditParent, e.g. ItemSku → Item): that record's type and id. */
    @Column(name = "parent_type", length = 100)
    private String parentType;

    @Column(name = "parent_id")
    private Long parentId;

    /** Shared by every log written in the same database transaction (null on logs that predate it). */
    @Column(name = "change_set", length = 36)
    private String changeSet;

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
     * CREATE / DELETE: full field snapshot — e.g. {"name":"Acme","active":"true","itemCategory":"3","tags":["A","B"]}
     * UPDATE: array of changed fields — e.g. [{"field":"name","oldValue":"X","newValue":"Y"}]
     * Associations are stored as the related record's id; collections as a sorted array of ids / values.
     */
    @Column(columnDefinition = "text")
    private String changes;
}
