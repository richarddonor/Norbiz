package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.BankAdjustmentType;
import com.chardizard.Norbiz.models.BankReconciliationLineKind;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
public class BankReconciliationLineResponse {
    private Long id;
    private BankReconciliationLineKind kind;
    private Long bankEntryId;
    private BankAdjustmentType adjustmentType;
    private Instant entryDate;
    private String referenceNumber;
    private String checkNumber;
    private String description;
    private BigDecimal amount;
    private Integer lineNumber;
}
