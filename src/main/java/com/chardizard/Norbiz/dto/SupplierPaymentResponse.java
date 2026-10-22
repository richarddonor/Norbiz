package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.TransactionOrigin;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class SupplierPaymentResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long supplierId;
    private String supplierName;
    private String referenceNumber;
    private String voucherNumber;
    private String sheetNumber;
    private Instant paymentDate;
    private String remarks;
    private BigDecimal totalAmount;
    private BigDecimal appliedAmount;
    private BigDecimal unappliedAmount;
    private Instant createdAt;
    private String createdBy;
    private boolean voided;
    private Instant voidedAt;
    private String voidedBy;
    private TransactionOrigin origin;
    private List<IssuedTenderResponse> tenders;
    private List<AllocationResponse> applications;
    // What the payment paid (per charge: from its tenders and from existing credits) and the existing credits it used.
    // Not filled for the rows a list's grand totals are computed from.
    private List<PaidDocumentResponse> paidDocuments;
    private List<CreditUsedResponse> creditsUsed;
    private BigDecimal creditsUsedAmount;
}
