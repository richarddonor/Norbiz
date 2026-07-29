package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class PurchaseReceiveResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long warehouseId;
    private String warehouseName;
    private Long supplierId;
    private String supplierName;
    // Exactly one of these pairs is non-null, matching the receive's own source.
    private Long purchaseOrderId;
    private String purchaseOrderReferenceNumber;
    private Long purchaseInvoiceId;
    private String purchaseInvoiceReferenceNumber;
    private String referenceNumber;
    private String sheetNumber;
    private Instant receiptDate;
    private String remarks;
    private Instant createdAt;
    private String createdBy;
    private boolean voided;
    private Instant voidedAt;
    private String voidedBy;
    private boolean loaded;
    private List<PurchaseReceiveLineResponse> lines;
}
