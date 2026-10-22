package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.OpenItemDirection;
import com.chardizard.Norbiz.models.OpenItemSourceType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
public class StatementOfAccountLineResponse {
    private Long id;
    private Long openItemId;
    private OpenItemSourceType sourceType;
    private OpenItemDirection direction;
    private String referenceNumber;
    private Instant documentDate;
    private Instant dueDate;
    private BigDecimal amount;
    private BigDecimal paidAmount;
    private BigDecimal pendingAmount;
    // Negative for unapplied credits.
    private BigDecimal balance;
    private int daysOverdue;
    private Integer lineNumber;
}
