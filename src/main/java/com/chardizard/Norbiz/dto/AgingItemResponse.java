package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.OpenItemDirection;
import com.chardizard.Norbiz.models.OpenItemSourceType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
public class AgingItemResponse {
    private Long openItemId;
    private OpenItemSourceType sourceType;
    private Long sourceId;
    private OpenItemDirection direction;
    private String referenceNumber;
    private Instant documentDate;
    private Instant dueDate;
    private BigDecimal amount;
    // As of the report date: applied so far, and the part of that still in uncleared checks.
    private BigDecimal paidAmount;
    private BigDecimal pendingAmount;
    private BigDecimal balance;
    // Charges only: days past due (or since the document date, by basis); 0 when not yet due.
    private int daysOverdue;
    // CURRENT, DAYS_1_30, DAYS_31_60, DAYS_61_90, DAYS_91_120, OVER_120; UNAPPLIED_CREDIT for credits.
    private String bucket;
}
