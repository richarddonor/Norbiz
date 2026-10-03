package com.chardizard.Norbiz.exceptions;

import lombok.Getter;

/**
 * Thrown when a record can't be deleted because other records still reference it (a Postgres
 * foreign-key violation, SQLState 23503, or an equivalent explicit pre-check). Mapped to
 * 409 CONFLICT with error code {@link #CODE} by GlobalExceptionHandler so the frontend can show
 * a "still in use" message instead of a generic failure.
 */
@Getter
public class EntityInUseException extends RuntimeException {

    public static final String CODE = "ENTITY_IN_USE";

    // Human-readable name of the record being deleted, e.g. "Brand". Null when unknown
    // (a raw FK violation that wasn't raised through ForeignKeyViolations.deleteOrThrow).
    private final String entity;
    private final Object entityId;
    // Human-readable name of the referencing table, e.g. "Purchase Order Line". Null when it
    // couldn't be parsed from the database error.
    private final String referencedBy;

    public EntityInUseException(String entity, Object entityId, String referencedBy) {
        this(entity, entityId, referencedBy, defaultMessage(entity, referencedBy));
    }

    public EntityInUseException(String entity, Object entityId, String referencedBy, String message) {
        super(message);
        this.entity = entity;
        this.entityId = entityId;
        this.referencedBy = referencedBy;
    }

    private static String defaultMessage(String entity, String referencedBy) {
        String subject = entity != null ? entity : "Record";
        if (referencedBy == null) return subject + " cannot be deleted because it is still used by other records";
        // Table-derived names may already be plural ("Inventory Movements") or not ("Item").
        String users = referencedBy.endsWith("s") ? referencedBy : referencedBy + " records";
        return subject + " cannot be deleted because it is still used by " + users;
    }
}
