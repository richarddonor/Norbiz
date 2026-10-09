package com.chardizard.Norbiz.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Dashboard widget: units added (+) and removed (−) by non-voided inventory adjustments per day. */
public record InventoryAdjustmentTrendResponse(
        int days,
        Instant from,
        Instant asOf,
        BigDecimal unitsAdded,
        // Positive number: units removed.
        BigDecimal unitsRemoved,
        long documentCount,
        List<DailyPoint> daily,
        // Top warehouses by total units moved (added + removed).
        List<WarehouseTotal> byWarehouse,
        // Most frequent reasons (trimmed, case-insensitive), by document count.
        List<ReasonCount> topReasons) {

    public record DailyPoint(Instant date, BigDecimal added, BigDecimal removed, long documents) {
    }

    public record WarehouseTotal(Long id, String name, BigDecimal added, BigDecimal removed) {
    }

    public record ReasonCount(String reason, long documentCount) {
    }
}
