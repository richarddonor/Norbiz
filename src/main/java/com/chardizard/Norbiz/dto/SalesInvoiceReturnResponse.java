package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.TransactionOrigin;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class SalesInvoiceReturnResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long customerId;
    private String customerName;
    private Long warehouseId;
    private String warehouseName;
    private String referenceNumber;
    private String sheetNumber;
    private Instant returnDate;
    private String remarks;
    // Stored at posting (2 decimals): the credit's amount.
    private BigDecimal netAmount;
    // The RECEIVABLE CREDIT; null when netAmount is zero.
    private Long openItemId;
    // How much of the credit is applied (to the returned invoices or, later, by payments/refunds) and what is left.
    private BigDecimal appliedAmount;
    private BigDecimal unappliedAmount;
    // Reference numbers of the returned invoices, in line order, without repeats (also printable).
    private String salesInvoiceReferenceNumbers;
    private Instant createdAt;
    private String createdBy;
    private boolean voided;
    private Instant voidedAt;
    private String voidedBy;
    private TransactionOrigin origin;
    private List<SalesInvoiceReturnLineResponse> lines;
    // Where the credit went: one row per active application (invoice reference + amount).
    private List<AllocationResponse> applications;
}
