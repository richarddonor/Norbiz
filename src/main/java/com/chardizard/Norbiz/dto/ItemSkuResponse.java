package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class ItemSkuResponse {
    private Long id;
    private Long itemId;
    private String itemCode;
    private String itemName;
    private String skuCode;
    private BigDecimal unitPrice;
}
