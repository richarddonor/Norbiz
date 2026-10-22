package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.TransactionOrigin;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class DepositResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long bankAccountId;
    private String bankAccountName;
    private String bankAccountNumber;
    private String referenceNumber;
    private String sheetNumber;
    private Instant depositDate;
    private String remarks;
    private BigDecimal totalAmount;
    // True once a Bank Reconciliation has cleared the deposit.
    private boolean reconciled;
    private Instant createdAt;
    private String createdBy;
    private boolean voided;
    private Instant voidedAt;
    private String voidedBy;
    private TransactionOrigin origin;
    // The deposited tenders, with their current status (a check may have cleared or bounced since).
    private List<ReceivedTenderResponse> lines;
}
