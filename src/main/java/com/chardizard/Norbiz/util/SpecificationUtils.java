package com.chardizard.Norbiz.util;

import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * Small helpers for building dynamic case-insensitive "contains" filters over
 * (possibly nested) entity fields, composed with Spring Data's Specification API.
 */
public final class SpecificationUtils {

    private SpecificationUtils() {
    }

    /** Case-insensitive LIKE %value% on a dotted path (e.g. "itemCategory.name").
     * Every segment but the last is resolved via a LEFT JOIN, so this works
     * uniformly for direct fields, @ManyToOne, and to-many associations.
     * Returns null (a no-op) when value is blank. */
    public static <T> Specification<T> containsIgnoreCase(String dotPath, String value) {
        if (!StringUtils.hasText(value)) return null;
        String pattern = "%" + value.trim().toLowerCase() + "%";
        return (root, query, cb) -> {
            query.distinct(true);
            return cb.like(cb.lower(resolvePath(root, dotPath).as(String.class)), pattern);
        };
    }

    /** Same as containsIgnoreCase, but OR's across several candidate paths —
     * useful when one frontend filter key can match more than one underlying field. */
    public static <T> Specification<T> anyContainsIgnoreCase(String value, String... dotPaths) {
        if (!StringUtils.hasText(value)) return null;
        String pattern = "%" + value.trim().toLowerCase() + "%";
        return (root, query, cb) -> {
            query.distinct(true);
            Predicate[] predicates = new Predicate[dotPaths.length];
            for (int i = 0; i < dotPaths.length; i++) {
                predicates[i] = cb.like(cb.lower(resolvePath(root, dotPaths[i]).as(String.class)), pattern);
            }
            return cb.or(predicates);
        };
    }

    /** Inclusive date-range filter on an Instant-typed dotted path. Either bound
     * may be null (open-ended range); returns null (a no-op) if both are null. */
    public static <T> Specification<T> dateRange(String dotPath, Instant from, Instant to) {
        if (from == null && to == null) return null;
        return (root, query, cb) -> {
            Path<Instant> path = resolvePath(root, dotPath);
            if (from != null && to != null) return cb.between(path, from, to);
            if (from != null) return cb.greaterThanOrEqualTo(path, from);
            return cb.lessThanOrEqualTo(path, to);
        };
    }

    /** Equality filter on a boolean-typed dotted path. Returns null (a no-op) when value is null. */
    public static <T> Specification<T> booleanEquals(String dotPath, Boolean value) {
        if (value == null) return null;
        return (root, query, cb) -> cb.equal(resolvePath(root, dotPath).as(Boolean.class), value);
    }

    public static <T> Specification<T> booleanEquals(String dotPath, String value) {
        if (!StringUtils.hasText(value)) return null;
        return booleanEquals(dotPath, Boolean.valueOf(value));
    }

    /** Folds a list of possibly-null Specifications into one, skipping nulls. */
    @SafeVarargs
    public static <T> Specification<T> allOf(Specification<T>... specs) {
        Specification<T> result = null;
        for (Specification<T> spec : specs) {
            if (spec == null) continue;
            result = (result == null) ? spec : result.and(spec);
        }
        return result != null ? result : (root, query, cb) -> cb.conjunction();
    }

    @SuppressWarnings("unchecked")
    private static <T, Y> Path<Y> resolvePath(From<?, T> root, String dotted) {
        String[] parts = dotted.split("\\.");
        From<?, ?> from = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Join<?, ?> join = from.join(parts[i], JoinType.LEFT);
            from = join;
        }
        return (Path<Y>) from.get(parts[parts.length - 1]);
    }
}
