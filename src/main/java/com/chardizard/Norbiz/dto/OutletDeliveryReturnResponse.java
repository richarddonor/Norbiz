package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.TransactionOrigin;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class OutletDeliveryReturnResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long outletDeliveryReceiptId;
    private String outletDeliveryReceiptReferenceNumber;
    private Long customerId;
    private String customerName;
    private Long warehouseId;
    private String warehouseName;
    private Long agentId;
    private String agentCode;
    private String agentName;
    private String referenceNumber;
    private String sheetNumber;
    private Instant returnDate;
    private String remarks;
    // Sum of line amounts — computed, not persisted.
    private BigDecimal totalAmount;
    private Instant createdAt;
    private String createdBy;
    private boolean voided;
    private Instant voidedAt;
    private String voidedBy;
    private boolean loaded;
    private TransactionOrigin origin;
    private List<OutletDeliveryReturnLineResponse> lines;
}
