package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.TransactionOrigin;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class BankReconciliationResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long bankAccountId;
    private String bankAccountName;
    private String bankAccountNumber;
    private String referenceNumber;
    private String sheetNumber;
    private Instant statementDate;
    private String remarks;
    private BigDecimal previousBalance;
    private BigDecimal statementEndingBalance;
    private BigDecimal clearedTotal;
    private BigDecimal adjustmentsTotal;
    private BigDecimal bookBalance;
    private BigDecimal outstandingTotal;
    private Instant createdAt;
    private String createdBy;
    private boolean voided;
    private Instant voidedAt;
    private String voidedBy;
    private TransactionOrigin origin;
    private List<BankReconciliationLineResponse> lines;
}
