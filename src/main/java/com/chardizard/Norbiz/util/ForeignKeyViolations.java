package com.chardizard.Norbiz.util;

import com.chardizard.Norbiz.exceptions.EntityInUseException;
import org.hibernate.TransientPropertyValueException;
import org.hibernate.proxy.HibernateProxy;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns Postgres foreign-key violations (SQLState 23503) raised while deleting master data into
 * {@link EntityInUseException}.
 *
 * <p>When the referencing row is already loaded in the same persistence context, Hibernate catches
 * the dangling reference at flush time itself (TransientPropertyValueException naming the deleted
 * entity) before anything reaches the database; deleteOrThrow treats that as the same condition.
 */
public final class ForeignKeyViolations {

    private static final String FOREIGN_KEY_VIOLATION = "23503";

    // Postgres: update or delete on table "brand" violates foreign key constraint "fk..." on table "item"
    private static final Pattern REFERENCING_TABLE =
            Pattern.compile("violates foreign key constraint \"[^\"]+\" on table \"([^\"]+)\"");

    private ForeignKeyViolations() {}

    /**
     * Deletes and flushes immediately so a FK violation surfaces here — where we still know what was
     * being deleted — rather than at commit, after the service method has returned.
     */
    public static <T> void deleteOrThrow(JpaRepository<T, ?> repository, T entity, String entityName, Object id) {
        try {
            repository.delete(entity);
            repository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw from(ex, entityName, id).orElseThrow(() -> ex);
        } catch (DataAccessException ex) {
            TransientPropertyValueException tpve = findCause(ex, TransientPropertyValueException.class);
            if (tpve == null || !entityClass(entity).getName().equals(tpve.getTransientEntityName())) {
                throw ex;
            }
            throw new EntityInUseException(entityName, id, humanizeEntity(tpve.getPropertyOwnerEntityName()));
        }
    }

    private static <E extends Throwable> E findCause(Throwable ex, Class<E> type) {
        for (Throwable t = ex; t != null && t.getCause() != t; t = t.getCause()) {
            if (type.isInstance(t)) return type.cast(t);
        }
        return null;
    }

    /** Empty if {@code ex} isn't a foreign-key violation. */
    public static Optional<EntityInUseException> from(Throwable ex, String entityName, Object id) {
        return findForeignKeyViolation(ex)
                .map(sql -> new EntityInUseException(entityName, id, referencingTable(sql)));
    }

    private static Optional<SQLException> findForeignKeyViolation(Throwable ex) {
        for (Throwable t = ex; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                for (SQLException s = sql; s != null; s = s.getNextException()) {
                    if (FOREIGN_KEY_VIOLATION.equals(s.getSQLState())) return Optional.of(s);
                }
            }
        }
        return Optional.empty();
    }

    // Best effort: the message is server-localized, so this is null on a non-English lc_messages.
    private static String referencingTable(SQLException ex) {
        if (ex.getMessage() == null) return null;
        Matcher m = REFERENCING_TABLE.matcher(ex.getMessage());
        return m.find() ? humanize(m.group(1)) : null;
    }

    private static Class<?> entityClass(Object entity) {
        return entity instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass()
                : entity.getClass();
    }

    // com.chardizard.Norbiz.models.PurchaseOrderLine -> "Purchase Order Line"
    private static String humanizeEntity(String entityName) {
        if (entityName == null) return null;
        String simple = entityName.substring(entityName.lastIndexOf('.') + 1);
        return humanize(simple.replaceAll("(?<=[a-z0-9])(?=[A-Z])", "_"));
    }

    // purchase_order_lines -> "Purchase Order Lines"
    private static String humanize(String table) {
        return Arrays.stream(table.split("_"))
                .filter(s -> !s.isEmpty())
                .map(s -> s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1))
                .collect(Collectors.joining(" "));
    }
}
