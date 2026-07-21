package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PurchaseInvoiceFeeResponse {
    private Long id;
    private String description;
    private BigDecimal amount;
}
