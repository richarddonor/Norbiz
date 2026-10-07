package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.TransactionOrigin;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class OutletPullOutResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long customerId;
    private String customerName;
    // Source: the outlet's own warehouse.
    private Long warehouseId;
    private String warehouseName;
    // Destination: the main warehouse holding the pull out in transit.
    private Long destinationWarehouseId;
    private String destinationWarehouseName;
    private Long pullOutReasonId;
    private String pullOutReasonName;
    private String referenceNumber;
    private String sheetNumber;
    private Instant pullOutDate;
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
    private List<OutletPullOutLineResponse> lines;
}
