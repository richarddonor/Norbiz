package com.chardizard.Norbiz.util;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Grand totals for a list endpoint's amount/quantity columns (see docs/LIST_FILTERING.md → Totals).
 *
 * Where a total is computed in Java (a header's Total Amount, a report line's discounted amount), a list
 * endpoint with {@code totals=true} re-runs its filtered query unpaged, maps every row with the same
 * response mapper the page uses (so a total always equals the sum of the cells shown — discounts, line
 * sums and permission gating included) and returns the sums keyed by the frontend's column key — but only
 * up to {@link #LIMIT} rows ({@link #within}); above it the response says so and the frontend asks the user
 * to narrow the filters. Endpoints whose totals are stored columns (the ledger, balances) aggregate in SQL
 * or from raw rows instead and have no limit. A column whose values are all null (e.g. a cost column the
 * caller may not see) is left out.
 */
public final class ListTotals {

    /** Swagger description of the {@code totals} request param. */
    public static final String PARAM = "true: also return `totals`, the grand totals of the amount/quantity columns over every row matching the filters";

    /** Most rows a Java-computed total will map — the same cap as the frontend's "search all rows". */
    public static final long LIMIT = 50_000;

    private ListTotals() {
    }

    /** The sums over {@code rows}, or empty (without loading them) when {@code matching} exceeds {@link #LIMIT}. */
    @SafeVarargs
    public static <R> Optional<Map<String, BigDecimal>> within(long matching, Supplier<List<R>> rows, Column<R>... columns) {
        if (matching > LIMIT) return Optional.empty();
        return Optional.of(sum(rows.get(), columns));
    }

    /** Adds {@code value} to {@code key}'s running total; a null value leaves it as it is. */
    public static void add(Map<String, BigDecimal> totals, String key, BigDecimal value) {
        if (value != null) totals.merge(key, value, BigDecimal::add);
    }

    /** One summed column: its frontend key and how to read it from a response row. */
    public record Column<R>(String key, Function<R, BigDecimal> value) {
    }

    public static <R> Column<R> column(String key, Function<R, BigDecimal> value) {
        return new Column<>(key, value);
    }

    @SafeVarargs
    public static <R> Map<String, BigDecimal> sum(List<R> rows, Column<R>... columns) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (Column<R> column : columns) {
            BigDecimal total = null;
            for (R row : rows) {
                BigDecimal value = column.value().apply(row);
                if (value != null) total = total == null ? value : total.add(value);
            }
            if (total != null) totals.put(column.key(), total);
        }
        return totals;
    }
}
