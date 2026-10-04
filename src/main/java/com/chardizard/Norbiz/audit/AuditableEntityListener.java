package com.chardizard.Norbiz.audit;

import com.chardizard.Norbiz.models.AuditLog;
import com.chardizard.Norbiz.models.Auditable;
import com.chardizard.Norbiz.repositories.AuditLogRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.proxy.HibernateProxy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.*;

/**
 * JPA entity listener registered on all Auditable entities.
 * Records field-level changes to the audit_logs table:
 *  - CREATE: full snapshot
 *  - UPDATE: array of {field, oldValue, newValue} for changed fields only
 *  - DELETE: full snapshot before deletion
 *
 * A snapshot holds scalar fields as strings, @ManyToOne/@OneToOne associations as the related
 * record's id, and @ElementCollection/@ManyToMany collections as a sorted list of element values
 * (ids for entities). @OneToMany children aren't snapshotted — they're audited on their own and
 * linked back to the parent through @AuditParent. Every log also carries the transaction's change set.
 *
 * Spring beans are accessed via ApplicationContextHolder because JPA entity
 * listeners are instantiated by the provider, not the Spring container.
 */
@Slf4j
public class AuditableEntityListener {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostLoad
    public void onPostLoad(Auditable entity) {
        // Capture the DB state so we can diff against it later in onPostUpdate.
        remember(entity);
    }

    @PostPersist
    public void onPostPersist(Auditable entity) {
        saveLog(entity, AuditAction.CREATE, snapshot(entity, false));
        remember(entity);
    }

    @PostUpdate
    public void onPostUpdate(Auditable entity) {
        String changes = diffChanges(entity);
        if (changes != null) {
            saveLog(entity, AuditAction.UPDATE, changes);
        }
        // A later flush in the same transaction must diff against what was just written.
        remember(entity);
    }

    @PreRemove
    public void onPreRemove(Auditable entity) {
        // Not inside a flush yet, so lazy collections can still be loaded for the final snapshot.
        saveLog(entity, AuditAction.DELETE, snapshot(entity, true));
    }

    // -----------------------------------------------------------------------
    // Change computation
    // -----------------------------------------------------------------------

    private void remember(Auditable entity) {
        try {
            entity.setOriginalSnapshot(MAPPER.writeValueAsString(extractFields(entity)));
            Map<String, Object> refs = new HashMap<>();
            for (Field field : collectionFields(entity)) {
                refs.put(field.getName(), field.get(entity));
            }
            entity.setOriginalCollections(refs);
        } catch (Exception ignored) {
            //Will and only should be raised during Application start
        }
    }

    /** Full snapshot as a JSON object. Lazy collections are loaded only when {@code initialize}. */
    private String snapshot(Auditable entity, boolean initialize) {
        try {
            Map<String, Object> fields = extractFields(entity);
            for (Field field : collectionFields(entity)) {
                Object value = field.get(entity);
                if (value instanceof PersistentCollection<?> pc && !pc.wasInitialized()) {
                    if (!initialize) continue;
                    Hibernate.initialize(pc);
                }
                fields.put(field.getName(), elements(value));
            }
            return MAPPER.writeValueAsString(fields);
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
                return snapshot(entity, false);
            }

            Map<String, Object> original = MAPPER.readValue(originalJson, new TypeReference<>() {});
            Map<String, Object> current  = extractFields(entity);

            List<Map<String, Object>> changes = new ArrayList<>();
            for (Map.Entry<String, Object> entry : current.entrySet()) {
                String key        = entry.getKey();
                Object currentVal = entry.getValue();
                Object originalVal = original.get(key);
                if (!Objects.equals(currentVal, originalVal)) {
                    changes.add(change(key, originalVal, currentVal));
                }
            }

            Map<String, Object> refs = entity.getOriginalCollections();
            for (Field field : collectionFields(entity)) {
                Object currentVal = field.get(entity);
                List<String> before = collectionBefore(refs != null ? refs.get(field.getName()) : null, currentVal);
                if (before == null) continue;
                List<String> after = elements(currentVal);
                if (!before.equals(after)) {
                    changes.add(change(field.getName(), before, after));
                }
            }

            if (changes.isEmpty()) return null;
            return MAPPER.writeValueAsString(changes);
        } catch (Exception e) {
            return snapshot(entity, false);
        }
    }

    private static Map<String, Object> change(String field, Object oldValue, Object newValue) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("field",    field);
        change.put("oldValue", oldValue);
        change.put("newValue", newValue);
        return change;
    }

    /**
     * Contents of a collection as of the last load/flush, or null when it can't have changed (the
     * same never-loaded collection is still in place) or can't be recovered.
     * {@code original} is the collection instance the entity held then; a setter may since have
     * replaced it, in which case the old instance still reflects the database.
     */
    private List<String> collectionBefore(Object original, Object current) {
        if (original instanceof PersistentCollection<?> pc) {
            if (!pc.wasInitialized()) {
                if (original == current) return null;
                try {
                    // Dereferenced but still attached until the flush completes; its rows haven't
                    // been deleted yet (collection actions run after entity updates).
                    Hibernate.initialize(pc);
                } catch (Exception e) {
                    log.debug("Could not load previous contents of a replaced collection: {}", e.getMessage());
                    return null;
                }
                return elements(pc);
            }
            Object stored = pc.getStoredSnapshot();
            if (stored instanceof Map<?, ?> map) return elements(map.keySet());   // PersistentSet / PersistentMap
            if (stored instanceof Collection<?> c) return elements(c);           // PersistentBag / PersistentList
            return null;
        }
        if (original instanceof Collection<?> c && original != current) return elements(c);
        return null;
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    private void saveLog(Auditable entity, AuditAction action, String changes) {
        try {
            AuditLog log = new AuditLog();
            log.setEntityType(entity.getClass().getSimpleName());
            log.setEntityId(asLong(idOf(entity)));
            log.setChangeSet(AuditChangeSet.current());
            Object parent = parentOf(entity);
            if (parent != null) {
                log.setParentType(classOf(parent).getSimpleName());
                log.setParentId(asLong(idOf(parent)));
            }
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
     * Extracts scalar fields (as strings) and to-one associations (as the related id) from the
     * entity hierarchy. Collections are handled separately; see {@link #collectionFields}.
     * Skips: transient, static, @AuditExclude and @AuditParent fields, and the snapshot carriers.
     */
    private Map<String, Object> extractFields(Object entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Field field : fields(entity)) {
            if (shouldSkip(field) || isCollection(field) || field.isAnnotationPresent(AuditParent.class)) continue;
            field.setAccessible(true);
            try {
                Object value = field.get(entity);
                if (isToOne(field)) value = value != null ? idOf(value) : null;
                map.put(field.getName(), value != null ? value.toString() : null);
            } catch (IllegalAccessException ignored) {}
        }
        return map;
    }

    /** @ElementCollection and @ManyToMany fields — @OneToMany children are audited on their own. */
    private List<Field> collectionFields(Object entity) {
        List<Field> result = new ArrayList<>();
        for (Field field : fields(entity)) {
            if (shouldSkip(field)) continue;
            if (field.isAnnotationPresent(ElementCollection.class) || field.isAnnotationPresent(ManyToMany.class)) {
                field.setAccessible(true);
                result.add(field);
            }
        }
        return result;
    }

    private Object parentOf(Object entity) throws IllegalAccessException {
        for (Field field : fields(entity)) {
            if (field.isAnnotationPresent(AuditParent.class)) {
                field.setAccessible(true);
                return field.get(entity);
            }
        }
        return null;
    }

    private static List<Field> fields(Object entity) {
        List<Field> result = new ArrayList<>();
        Class<?> clazz = entity.getClass();
        while (clazz != null && clazz != Object.class) {
            result.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return result;
    }

    private boolean shouldSkip(Field field) {
        int mods = field.getModifiers();
        return Modifier.isStatic(mods)
            || field.isAnnotationPresent(Transient.class)
            || field.isAnnotationPresent(OneToMany.class)
            || field.isAnnotationPresent(AuditExclude.class)
            || "originalSnapshot".equals(field.getName())
            || "originalCollections".equals(field.getName());
    }

    private static boolean isToOne(Field field) {
        return field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class);
    }

    private static boolean isCollection(Field field) {
        return Collection.class.isAssignableFrom(field.getType()) || Map.class.isAssignableFrom(field.getType());
    }

    /** Sorted string form of a collection's elements; entities become their ids. */
    private static List<String> elements(Object collection) {
        if (!(collection instanceof Collection<?> c)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object element : c) {
            if (element == null) continue;
            Object value = isEntity(element) ? idOf(element) : element;
            result.add(value instanceof Enum<?> e ? e.name() : String.valueOf(value));
        }
        Collections.sort(result);
        return result;
    }

    private static boolean isEntity(Object value) {
        return value instanceof HibernateProxy || value.getClass().isAnnotationPresent(Entity.class);
    }

    /** Primary key without initializing a lazy proxy. */
    private static Object idOf(Object entity) {
        if (entity instanceof HibernateProxy proxy) {
            return proxy.getHibernateLazyInitializer().getInternalIdentifier();
        }
        Class<?> clazz = entity.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    field.setAccessible(true);
                    try {
                        return field.get(entity);
                    } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /** Entity class without initializing a lazy proxy (Hibernate.getClass would). */
    private static Class<?> classOf(Object entity) {
        return entity instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass()
                : entity.getClass();
    }

    private static Long asLong(Object id) {
        return id instanceof Long l ? l : null;
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return "system";
        }
        return auth.getName();
    }
}
