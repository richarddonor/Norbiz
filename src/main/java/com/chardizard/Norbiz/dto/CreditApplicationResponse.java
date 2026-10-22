package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.TransactionOrigin;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// Customer or supplier credit application — the other side's party fields are null.
@Getter
@Setter
public class CreditApplicationResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long customerId;
    private String customerName;
    private Long supplierId;
    private String supplierName;
    private String referenceNumber;
    private String sheetNumber;
    private Instant applicationDate;
    private String remarks;
    private BigDecimal totalAmount;
    private Instant createdAt;
    private String createdBy;
    private boolean voided;
    private Instant voidedAt;
    private String voidedBy;
    private TransactionOrigin origin;
    private List<CreditApplicationLineResponse> lines;
    // Current state of each application (reversed = voided, or undone because the paying check bounced/was cancelled).
    private List<AllocationResponse> applications;
}
