package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class OutletPullOutLineResponse {
    private Long id;
    private Long itemId;
    private String itemCode;
    private String itemName;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    // quantity × unitPrice — computed, not persisted.
    private BigDecimal amount;
    private Integer lineNumber;
    private BigDecimal quantityLoaded;
}
