package com.chardizard.Norbiz.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// Dropdown option for picking a source transaction (e.g. the Purchase Order a Receive loads from).
// Carries supplier/warehouse so the form can pre-fill them, and the lines the consuming form copies
// or receives against. Line costPrice is null unless the caller holds VIEW_COST_PRICE.
@Getter
@AllArgsConstructor
public class TransactionLookupResponse {
    private Long id;
    private Long companyId;
    private String referenceNumber;
    private Instant transactionDate;
    private Long supplierId;
    private String supplierName;
    private Long warehouseId;
    private String warehouseName;
    // Purchase Invoice only: the originating PO for a PO-based invoice, null for a Direct one.
    private Long purchaseOrderId;
    private boolean voided;
    private boolean loaded;
    private List<Line> lines;

    @Getter
    @AllArgsConstructor
    public static class Line {
        private Long id;
        private Integer lineNumber;
        private Long itemId;
        private String itemCode;
        private String itemName;
        private BigDecimal quantity;
        private BigDecimal quantityLoaded;
        private BigDecimal costPrice;
    }
}
