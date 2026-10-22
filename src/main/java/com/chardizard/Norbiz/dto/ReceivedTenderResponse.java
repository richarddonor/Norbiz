package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.BounceReason;
import com.chardizard.Norbiz.models.ReceivedCheckStatus;
import com.chardizard.Norbiz.models.ReceivedPaymentMode;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

// A received tender (customer payment line). Payment header fields are included so the received-checks list can show it alone.
@Getter
@Setter
public class ReceivedTenderResponse {
    private Long id;
    private Long paymentId;
    private String paymentReferenceNumber;
    private Instant paymentDate;
    private Long companyId;
    private Long customerId;
    private String customerName;
    private Integer lineNumber;
    private ReceivedPaymentMode mode;
    private BigDecimal amount;
    private String checkNumber;
    private Instant checkDate;
    private Long bankId;
    private String bankName;
    private Long depositBankAccountId;
    private String depositBankAccountName;
    private String officialReceiptNumber;
    private String provisionalReceiptNumber;
    private ReceivedCheckStatus status;
    private Instant statusDate;
    private BounceReason bounceReason;
    private Long depositId;
    private String depositReferenceNumber;
    private Long replacesTenderId;
    private BigDecimal replacedAmount;
    private Long openItemId;
    // Applied to charges so far / still unapplied (an advance).
    private BigDecimal appliedAmount;
    private BigDecimal unappliedAmount;
    private boolean paymentVoided;
}
