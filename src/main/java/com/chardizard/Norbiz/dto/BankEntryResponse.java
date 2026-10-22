package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.BankEntryType;
import com.chardizard.Norbiz.models.TransactionType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
public class BankEntryResponse {
    private Long id;
    private Long bankAccountId;
    private Instant entryDate;
    private BankEntryType entryType;
    // Signed: money in is positive, money out negative.
    private BigDecimal amount;
    private TransactionType sourceType;
    private Long sourceId;
    private Long sourceLineId;
    private String referenceNumber;
    private String checkNumber;
    private Instant checkDate;
    private String description;
    private boolean cleared;
    private Instant clearedDate;
    private Long bankReconciliationId;
    private String bankReconciliationReferenceNumber;
    private boolean voided;
    // Bank book only: balance after this entry.
    private BigDecimal runningBalance;
}
