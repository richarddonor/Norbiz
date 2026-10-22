package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// What a new reconciliation of an account starts from.
@Getter
@Setter
public class BankReconciliationCandidatesResponse {
    private Long bankAccountId;
    private Instant statementDate;
    // The previous reconciliation's ending balance, else the account's opening balance.
    private BigDecimal previousBalance;
    private Long previousReconciliationId;
    private String previousReconciliationReferenceNumber;
    private Instant previousStatementDate;
    // Book balance as of statementDate (opening balance + every non-voided entry up to it).
    private BigDecimal bookBalance;
    // Book entries up to statementDate the bank hasn't cleared yet — tick the ones on the statement.
    private List<BankEntryResponse> entries;
}
