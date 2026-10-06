package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class OutletDeliveryReceiptResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    // The outlet that made the sale.
    private Long customerId;
    private String customerName;
    // The outlet's warehouse the stock left from.
    private Long warehouseId;
    private String warehouseName;
    private Long agentId;
    private String agentCode;
    private String agentName;
    private String referenceNumber;
    private String sheetNumber;
    private Instant deliveryDate;
    private String remarks;
    // Sum of line amounts — computed, not persisted.
    private BigDecimal totalAmount;
    private Instant createdAt;
    private String createdBy;
    private boolean voided;
    private Instant voidedAt;
    private String voidedBy;
    private boolean loaded;
    private List<OutletDeliveryReceiptLineResponse> lines;
}
