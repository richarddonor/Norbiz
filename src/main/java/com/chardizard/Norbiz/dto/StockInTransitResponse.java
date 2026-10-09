package com.chardizard.Norbiz.dto;

import java.math.BigDecimal;
import java.util.List;

/** Dashboard widget: current in-transit vs on-hand quantity per warehouse (from InventoryBalance). */
public record StockInTransitResponse(
        BigDecimal transitQuantity,
        BigDecimal onHandQuantity,
        long warehousesWithTransit,
        long itemsInTransit,
        // MAIN / OUTLET / OTHER
        List<KindTotal> byKind,
        // Top warehouses by transit quantity, plus one "Others" row with a null id.
        List<WarehouseTotal> warehouses) {

    public record KindTotal(String kind, long warehouseCount, BigDecimal transitQuantity, BigDecimal onHandQuantity) {
    }

    public record WarehouseTotal(Long id, String name, String kind, long itemsInTransit,
                                 BigDecimal transitQuantity, BigDecimal onHandQuantity) {
    }
}
