package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
public class InventoryMovementResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long itemId;
    private String itemCode;
    private String itemName;
    private Long warehouseId;
    private String warehouseName;
    private BigDecimal quantityDelta;
    private BigDecimal transitQuantityDelta;
    private Instant movementDate;
    private String sourceType;
    private Long sourceId;
    private String referenceNumber;
    private String sheetNumber;
    private String notes;
    private Instant createdAt;
    private String createdBy;
}