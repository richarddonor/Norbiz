package com.chardizard.Norbiz.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.HashMap;
import java.util.Map;

/**
 * Column sorting for list endpoints (see docs/LIST_FILTERING.md → Sorting).
 *
 * The frontend sends {@code sort=<columnKey>,asc|desc} using its own column keys; each endpoint maps the
 * keys it can sort by to entity paths (e.g. {@code company → company.name}). An unknown key is a 400, so
 * a client can never order by an arbitrary — possibly permission-gated — field. The endpoint's default
 * order (newest first) follows the requested one, then {@code id DESC}, so pages are stable for export
 * and search-all, which walk every page.
 */
public final class ListSort {

    /** Master data and other {@code Auditable} entities: last updated first. */
    public static final Sort UPDATED_FIRST = Sort.by(Sort.Direction.DESC, "updatedAt");
    /** Transactions and ledger rows are immutable (no {@code updatedAt}): newest first. */
    public static final Sort CREATED_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private ListSort() {
    }

    /** Builds a column-key → entity-path map from alternating pairs: {@code fields("company", "company.name", "name", "name")}. */
    public static Map<String, String> fields(String... keyPathPairs) {
        Map<String, String> fields = new HashMap<>();
        for (int i = 0; i < keyPathPairs.length; i += 2) fields.put(keyPathPairs[i], keyPathPairs[i + 1]);
        return Map.copyOf(fields);
    }

    /** {@code pageable} with its sort mapped through {@code sortable}, then {@code defaultSort}, then {@code id DESC}. */
    public static Pageable apply(Pageable pageable, Map<String, String> sortable, Sort defaultSort) {
        Sort sort = resolve(pageable.getSort(), sortable).and(defaultSort).and(Sort.by(Sort.Direction.DESC, "id"));
        return pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort)
                : Pageable.unpaged(sort);
    }

    /** Just the requested orders, mapped through {@code sortable} — for callers that add their own tail. */
    public static Sort resolve(Sort requested, Map<String, String> sortable) {
        Sort sort = Sort.unsorted();
        for (Sort.Order order : requested) {
            String path = sortable.get(order.getProperty());
            if (path == null) throw new IllegalArgumentException("Cannot sort by '" + order.getProperty() + "'");
            sort = sort.and(Sort.by(order.getDirection(), path));
        }
        return sort;
    }

    /** The same query, every row: for totals over the whole filtered set. */
    public static Pageable unpaged(Pageable pageable) {
        return Pageable.unpaged(pageable.getSort());
    }
}
