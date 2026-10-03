package com.chardizard.Norbiz.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// Current stock of one item in one warehouse, shown beside a transaction line while it is being
// created (a guide to whether enough stock is on hand / in transit). Only the two live quantities —
// no cost or value — so it can be opened to transaction creators without VIEW_INVENTORY_REPORT.
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class StockLookupResponse {
    private Long itemId;
    private Long warehouseId;
    private BigDecimal quantity;
    private BigDecimal transitQuantity;
}
