package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.TransactionOrigin;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class OutletReceiveResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long deliveryReceiptId;
    private String deliveryReceiptReferenceNumber;
    private Long customerId;
    private String customerName;
    private Long warehouseId;
    private String warehouseName;
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
    private TransactionOrigin origin;
    private List<OutletReceiveLineResponse> lines;
}
