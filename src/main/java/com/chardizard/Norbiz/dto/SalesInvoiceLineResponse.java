package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class SalesInvoiceLineResponse {
    private Long id;
    private Long itemId;
    private String itemCode;
    private String itemName;
    // The billed DR line; null for Direct invoices.
    private Long deliveryReceiptLineId;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal discountPercentage;
    // quantity * unitPrice * (1 - discountPercentage / 100)
    private BigDecimal lineNet;
    private Integer lineNumber;
    // Quantity returned so far by Sales Invoice Returns.
    private BigDecimal quantityLoaded;
}
