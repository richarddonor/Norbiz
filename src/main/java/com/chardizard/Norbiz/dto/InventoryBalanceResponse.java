package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class InventoryBalanceResponse {
    private Long companyId;
    private String companyName;
    private Long itemId;
    private String itemCode;
    private String itemName;
    private Long warehouseId;
    private String warehouseName;
    // quantity/transitQuantity are populated in "current" and "as of date" modes only.
    private BigDecimal quantity;
    private BigDecimal transitQuantity;
    // beginning/ending/net are populated in "date range" mode only (startDate+endDate).
    private BigDecimal beginningQuantity;
    private BigDecimal beginningTransitQuantity;
    private BigDecimal endingQuantity;
    private BigDecimal endingTransitQuantity;
    private BigDecimal netQuantityChange;
    private BigDecimal netTransitQuantityChange;
    // costPrice/value are omitted (left null) for callers without VIEW_COST_PRICE.
    private BigDecimal costPrice;
    private BigDecimal value;
}