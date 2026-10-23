package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.PaymentStatus;
import com.chardizard.Norbiz.models.TransactionOrigin;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class SalesInvoiceResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long customerId;
    private String customerName;
    private Long warehouseId;
    private String warehouseName;
    // Null for Direct invoices.
    private Long deliveryReceiptId;
    private String deliveryReceiptReferenceNumber;
    private String referenceNumber;
    private String sheetNumber;
    private Instant invoiceDate;
    private int termDays;
    private Instant dueDate;
    private String remarks;
    private BigDecimal discountPercentage;
    // Sum of line nets, before the header discount.
    private BigDecimal itemsSubtotal;
    private BigDecimal discountAmount;
    // Stored at posting (2 decimals): the receivable's amount.
    private BigDecimal netAmount;
    private PaymentStatus paymentStatus;
    private Long openItemId;
    private BigDecimal appliedAmount;
    // Part of appliedAmount still in uncleared checks.
    private BigDecimal pendingAmount;
    // Payments reversed so far because their checks bounced (informational).
    private BigDecimal bouncedAmount;
    private BigDecimal balance;
    private Instant createdAt;
    private String createdBy;
    private boolean voided;
    // Fully returned (every line came back on Sales Invoice Returns).
    private boolean loaded;
    private Instant voidedAt;
    private String voidedBy;
    private TransactionOrigin origin;
    private List<SalesInvoiceLineResponse> lines;
}
