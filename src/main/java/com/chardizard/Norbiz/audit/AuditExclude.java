package com.chardizard.Norbiz.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Fields annotated with @AuditExclude are omitted from audit log snapshots.
 * Use this for sensitive fields such as passwords or tokens.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AuditExclude {
}
