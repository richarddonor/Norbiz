package com.chardizard.Norbiz.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Dashboard widget: transactions posted (CREATED events) per day for each transaction type over the last
 * {@code days} days, bucketed by the caller's time zone. Voids are counted separately.
 */
public record TransactionActivityResponse(
        int days,
        LocalDate from,
        LocalDate asOf,
        long totalCreated,
        long totalVoided,
        LocalDate busiestDay,
        long busiestDayCount,
        List<LocalDate> dates,
        // Only types with activity in the period, busiest first.
        List<TypeRow> types) {

    /** {@code counts} aligns with {@code dates}. */
    public record TypeRow(String transactionType, long created, long voided, List<Long> counts) {
    }
}
