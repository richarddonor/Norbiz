package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PurchaseInvoiceLineResponse {
    private Long id;
    private Long itemId;
    private String itemCode;
    private String itemName;
    // Null for Direct-mode lines.
    private Long purchaseOrderLineId;
    private BigDecimal quantity;
    // Null when the caller lacks VIEW_COST_PRICE — see PurchaseInvoiceController.toLineResponse.
    private BigDecimal costPrice;
    private BigDecimal discountPercentage;
    // quantity * costPrice * (1 - discountPercentage / 100). Null when the caller lacks VIEW_COST_PRICE.
    private BigDecimal lineNet;
    private BigDecimal quantityLoaded;
}
