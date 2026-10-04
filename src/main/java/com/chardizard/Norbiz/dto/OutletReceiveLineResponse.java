package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class OutletReceiveLineResponse {
    private Long id;
    private Long itemId;
    private String itemCode;
    private String itemName;
    private Long deliveryReceiptLineId;
    private BigDecimal quantity;
    private Integer lineNumber;
    private BigDecimal quantityLoaded;
}
