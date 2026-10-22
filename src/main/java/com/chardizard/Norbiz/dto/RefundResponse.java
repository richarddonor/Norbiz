package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.TransactionOrigin;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// Customer or supplier refund — the other side's party fields are null.
@Getter
@Setter
public class RefundResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long customerId;
    private String customerName;
    private Long supplierId;
    private String supplierName;
    private String referenceNumber;
    private String sheetNumber;
    private Instant refundDate;
    private String remarks;
    private BigDecimal amount;
    // IssuedPaymentMode (customer refunds) or ReceivedPaymentMode (supplier refunds).
    private String mode;
    private Long bankAccountId;
    private String bankAccountName;
    private String checkNumber;
    private Instant checkDate;
    private Long bankId;
    private String bankName;
    // Customer refunds only: the issued instrument's status (ISSUED until reconciled, then CLEARED).
    private String status;
    private Long openItemId;
    private Instant createdAt;
    private String createdBy;
    private boolean voided;
    private Instant voidedAt;
    private String voidedBy;
    private TransactionOrigin origin;
    private List<RefundLineResponse> lines;
}
