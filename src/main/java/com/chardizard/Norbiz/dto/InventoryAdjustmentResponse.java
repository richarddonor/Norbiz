package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class InventoryAdjustmentResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long warehouseId;
    private String warehouseName;
    private String referenceNumber;
    private String sheetNumber;
    private Instant adjustmentDate;
    private String reason;
    private Instant createdAt;
    private String createdBy;
    private List<InventoryAdjustmentLineResponse> lines;
}