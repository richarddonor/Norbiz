package com.chardizard.Norbiz.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the @ManyToOne field pointing at the record this entity is a part of (e.g. ItemSku.item).
 * Its audit logs are stamped with that parent's type/id, so they show up in the parent's change
 * history, and the field itself is left out of the snapshot.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AuditParent {
}
