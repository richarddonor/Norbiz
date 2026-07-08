package com.chardizard.Norbiz.audit;

import com.chardizard.Norbiz.models.AuditLog;
import com.chardizard.Norbiz.models.Auditable;
import com.chardizard.Norbiz.repositories.AuditLogRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.*;

/**
 * JPA entity listener registered on all Auditable entities.
 * Records field-level changes to the audit_logs table:
 *  - CREATE: full snapshot of scalar fields
 *  - UPDATE: array of {field, oldValue, newValue} for changed fields only
 *  - DELETE: full snapshot of scalar fields before deletion
 *
 * Spring beans are accessed via ApplicationContextHolder because JPA entity
 * listeners are instantiated by the provider, not the Spring container.
 */
@Slf4j
public class AuditableEntityListener {

    @PostLoad
    public void onPostLoad(Auditable entity) {
        // Capture the DB state so we can diff against it later in onPostUpdate.
        try {
            ObjectMapper mapper = new ObjectMapper();
            if (mapper == null) return;
            entity.setOriginalSnapshot(mapper.writeValueAsString(extractFields(entity)));
        } catch (Exception ignored) {
            //Will and only should be raised during Application start
            //log.error(ignored.getMessage(), ignored);
        }
    }

    @PostPersist
    public void onPostPersist(Auditable entity) {
        saveLog(entity, AuditAction.CREATE, snapshot(entity));
    }

    @PostUpdate
    public void onPostUpdate(Auditable entity) {
        String changes = diffChanges(entity);
        if (changes != null) {
            saveLog(entity, AuditAction.UPDATE, changes);
        }
    }

    @PreRemove
    public void onPreRemove(Auditable entity) {
        saveLog(entity, AuditAction.DELETE, snapshot(entity));
    }

    // -----------------------------------------------------------------------
    // Change computation
    // -----------------------------------------------------------------------

    /** Full scalar-field snapshot as a JSON object. */
    private String snapshot(Auditable entity) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            if (mapper == null) return "{}";
            return mapper.writeValueAsString(extractFields(entity));
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Returns a JSON array of changed fields, or null if nothing changed.
     * Falls back to a full snapshot when no original state was captured.
     */
    private String diffChanges(Auditable entity) {
        try {
            String originalJson = entity.getOriginalSnapshot();
            if (originalJson == null) {
                // Entity was never loaded from DB (e.g. created then immediately updated)
                return snapshot(entity);
            }

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> original = mapper.readValue(originalJson, new TypeReference<>() {});
            Map<String, Object> current  = extractFields(entity);

            List<Map<String, Object>> changes = new ArrayList<>();
            for (Map.Entry<String, Object> entry : current.entrySet()) {
                String key        = entry.getKey();
                Object currentVal = entry.getValue();
                Object originalVal = original.get(key);
                if (!Objects.equals(currentVal, originalVal)) {
                    Map<String, Object> change = new LinkedHashMap<>();
                    change.put("field",    key);
                    change.put("oldValue", originalVal);
                    change.put("newValue", currentVal);
                    changes.add(change);
                }
            }

            if (changes.isEmpty()) return null;
            return mapper.writeValueAsString(changes);
        } catch (Exception e) {
            return snapshot(entity);
        }
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    private void saveLog(Auditable entity, AuditAction action, String changes) {
        try {
            AuditLog log = new AuditLog();
            log.setEntityType(entity.getClass().getSimpleName());
            log.setEntityId(entityId(entity));
            log.setAction(action);
            log.setChangedBy(currentUsername());
            log.setChangedAt(Instant.now());
            log.setChanges(changes);

            AuditLogRepository repo = ApplicationContextHolder.getBean(AuditLogRepository.class);
            repo.save(log);
        } catch (Exception ignored) {
            // Audit failures must never break the main operation
        }
    }

    // -----------------------------------------------------------------------
    // Reflection helpers
    // -----------------------------------------------------------------------

    /**
     * Extracts all auditable scalar fields from the entity hierarchy.
     * Skips: relations, collections, transient fields, @AuditExclude fields,
     * the originalSnapshot carrier, and static fields.
     */
    private Map<String, Object> extractFields(Object entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        Class<?> clazz = entity.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (shouldSkip(field)) continue;
                field.setAccessible(true);
                try {
                    Object value = field.get(entity);
                    map.put(field.getName(), value != null ? value.toString() : null);
                } catch (IllegalAccessException ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
        return map;
    }

    private boolean shouldSkip(Field field) {
        int mods = field.getModifiers();
        return Modifier.isStatic(mods)
            || field.isAnnotationPresent(Transient.class)
            || field.isAnnotationPresent(ManyToOne.class)
            || field.isAnnotationPresent(OneToMany.class)
            || field.isAnnotationPresent(ManyToMany.class)
            || field.isAnnotationPresent(OneToOne.class)
            || field.isAnnotationPresent(AuditExclude.class)
            || Collection.class.isAssignableFrom(field.getType())
            || "originalSnapshot".equals(field.getName());
    }

    private Long entityId(Object entity) {
        Class<?> clazz = entity.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    field.setAccessible(true);
                    try {
                        Object val = field.get(entity);
                        return val instanceof Long l ? l : null;
                    } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return "system";
        }
        return auth.getName();
    }
}
