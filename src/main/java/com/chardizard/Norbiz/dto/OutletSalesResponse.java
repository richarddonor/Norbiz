package com.chardizard.Norbiz.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Dashboard widget: Outlet Delivery Receipt sales net of Outlet Delivery Returns over the last {@code days} days
 * (inclusive of asOf). Voided documents are excluded. Amounts are quantity × line unit price.
 */
public record OutletSalesResponse(
        int days,
        Instant from,
        Instant asOf,
        BigDecimal grossSales,
        BigDecimal returns,
        BigDecimal netSales,
        long documentCount,
        // Net sales of the equally long period just before this one; null-safe zero when none.
        BigDecimal previousNetSales,
        // One point per day, oldest first, zero-filled.
        List<DailyPoint> daily,
        List<Ranked> topOutlets,
        List<Ranked> topAgents) {

    public record DailyPoint(Instant date, BigDecimal sales, BigDecimal returns, BigDecimal net) {
    }

    public record Ranked(Long id, String name, BigDecimal netSales) {
    }
}
