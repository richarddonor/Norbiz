package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.TransactionOrigin;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class StatementOfAccountResponse {
    // Null for a preview.
    private Long id;
    private Long companyId;
    private String companyName;
    private Long customerId;
    private String customerName;
    private String referenceNumber;
    private String sheetNumber;
    private Instant statementDate;
    private String remarks;
    private BigDecimal totalBalance;
    private BigDecimal currentAmount;
    private BigDecimal days1To30;
    private BigDecimal days31To60;
    private BigDecimal days61To90;
    private BigDecimal days91To120;
    private BigDecimal over120;
    private BigDecimal unappliedCredits;
    private Instant createdAt;
    private String createdBy;
    private boolean voided;
    private Instant voidedAt;
    private String voidedBy;
    private TransactionOrigin origin;
    private List<StatementOfAccountLineResponse> lines;
}
