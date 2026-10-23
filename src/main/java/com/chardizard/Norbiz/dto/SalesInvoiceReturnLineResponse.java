package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class SalesInvoiceReturnLineResponse {
    private Long id;
    private Long itemId;
    private String itemCode;
    private String itemName;
    // Null only on migrated legacy lines that named no invoice.
    private Long salesInvoiceLineId;
    private Long salesInvoiceId;
    private String salesInvoiceReferenceNumber;
    private BigDecimal quantity;
    // The invoice line's net price per unit (its discounts folded in).
    private BigDecimal unitPrice;
    // quantity × unitPrice — computed, not persisted.
    private BigDecimal amount;
    private Integer lineNumber;
    // False only on migrated lines legacy never posted to stock.
    private boolean postsStock;
}
