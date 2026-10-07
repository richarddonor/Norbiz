package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PullOutReceiveLineResponse {
    private Long id;
    private Long itemId;
    private String itemCode;
    private String itemName;
    private Long outletPullOutLineId;
    private BigDecimal quantity;
    private Integer lineNumber;
    private BigDecimal quantityLoaded;
}
