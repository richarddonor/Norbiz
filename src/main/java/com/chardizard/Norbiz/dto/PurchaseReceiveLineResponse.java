package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PurchaseReceiveLineResponse {
    private Long id;
    private Long itemId;
    private String itemCode;
    private String itemName;
    // Exactly one of these is non-null, matching the receive's own source.
    private Long purchaseOrderLineId;
    private Long purchaseInvoiceLineId;
    private BigDecimal quantity;
    private BigDecimal quantityLoaded;
}
