package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.TransactionOrigin;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class CustomerPaymentResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long customerId;
    private String customerName;
    private String referenceNumber;
    private String sheetNumber;
    private Instant paymentDate;
    private String remarks;
    private BigDecimal totalAmount;
    // Sum over tenders of what is applied to charges / still unapplied (the customer's advance from this payment).
    private BigDecimal appliedAmount;
    private BigDecimal unappliedAmount;
    private Instant createdAt;
    private String createdBy;
    private boolean voided;
    private Instant voidedAt;
    private String voidedBy;
    private TransactionOrigin origin;
    private List<ReceivedTenderResponse> tenders;
    // Applications this payment made when posted (later ones are on Customer Credit Applications).
    private List<AllocationResponse> applications;
}
