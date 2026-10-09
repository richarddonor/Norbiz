package com.chardizard.Norbiz.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Dashboard widget: stock health at outlets for items they actually sell. Every outlet/item pair with outlet
 * sales in the last {@code days} days is rated by days of cover = on-hand ÷ (units sold ÷ days).
 * The grid shows the busiest outlets × best-selling items.
 */
public record OutletStockHealthResponse(
        int days,
        Instant from,
        Instant asOf,
        long pairsTracked,
        long stockOuts,
        long lowCover,
        int lowCoverDays,
        List<Ref> outlets,
        List<Ref> items,
        // One cell per outlet × item in the grid, row-major by outlets then items.
        List<Cell> cells,
        // The worst pairs overall (stock-outs first, then lowest cover).
        List<Alert> alerts) {

    public record Ref(Long id, String code, String name) {
    }

    /** {@code daysOfCover} is null when the pair had no sales in the period (no rate to divide by). */
    public record Cell(Long outletId, Long itemId, BigDecimal onHand, BigDecimal soldQuantity, BigDecimal daysOfCover) {
    }

    public record Alert(Long outletId, String outletName, Long itemId, String itemCode, String itemName,
                        BigDecimal onHand, BigDecimal soldQuantity, BigDecimal daysOfCover) {
    }
}
