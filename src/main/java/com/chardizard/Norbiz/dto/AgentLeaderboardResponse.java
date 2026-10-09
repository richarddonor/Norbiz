package com.chardizard.Norbiz.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Dashboard widget: agents ranked by net outlet sales (ODR sales − ODR returns) over the last {@code days} days —
 * the base their commission is computed on. Includes each agent's zero-filled daily net for a sparkline.
 */
public record AgentLeaderboardResponse(
        int days,
        Instant from,
        Instant asOf,
        long activeAgents,
        BigDecimal netSales,
        List<Agent> agents) {

    public record Agent(int rank, Long id, String name, BigDecimal netSales, BigDecimal grossSales, BigDecimal returns,
                        long documentCount, BigDecimal previousNetSales,
                        // Previous period's rank, null when the agent had no sales then.
                        Integer previousRank,
                        List<BigDecimal> dailyNet) {
    }
}
