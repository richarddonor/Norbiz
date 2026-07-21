package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PurchaseOrderLineResponse {
    private Long id;
    private Long itemId;
    private String itemCode;
    private String itemName;
    private BigDecimal quantity;
    // Null when the caller lacks VIEW_COST_PRICE — see PurchaseOrderController.toLineResponse.
    private BigDecimal costPrice;
    private BigDecimal quantityLoaded;
}
