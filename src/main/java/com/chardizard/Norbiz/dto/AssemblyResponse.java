package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.TransactionOrigin;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class AssemblyResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long warehouseId;
    private String warehouseName;
    private String referenceNumber;
    private String sheetNumber;
    private Instant assemblyDate;
    private String remarks;
    private Instant createdAt;
    private String createdBy;
    private boolean voided;
    private Instant voidedAt;
    private String voidedBy;
    private boolean loaded;
    private TransactionOrigin origin;
    // Finished items produced, and raw materials consumed — both in entry order.
    private List<AssemblyLineResponse> outputs;
    private List<AssemblyLineResponse> materials;
}
