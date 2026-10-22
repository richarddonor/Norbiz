package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.IssuedCancelReason;
import com.chardizard.Norbiz.models.IssuedCheckStatus;
import com.chardizard.Norbiz.models.IssuedPaymentMode;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

// An issued tender (supplier payment line). Payment header fields are included so the issued-checks list can show it alone.
@Getter
@Setter
public class IssuedTenderResponse {
    private Long id;
    private Long paymentId;
    private String paymentReferenceNumber;
    private String voucherNumber;
    private Instant paymentDate;
    private Long companyId;
    private Long supplierId;
    private String supplierName;
    private Integer lineNumber;
    private IssuedPaymentMode mode;
    private BigDecimal amount;
    private Long bankAccountId;
    private String bankAccountName;
    private String checkNumber;
    private Instant checkDate;
    private IssuedCheckStatus status;
    private Instant statusDate;
    private IssuedCancelReason cancelReason;
    private Long replacesTenderId;
    private Long openItemId;
    private BigDecimal appliedAmount;
    private BigDecimal unappliedAmount;
    private boolean paymentVoided;
}
