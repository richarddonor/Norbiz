package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.AllocationReversalReason;
import com.chardizard.Norbiz.models.OpenItemSourceType;
import com.chardizard.Norbiz.models.TransactionType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

// One application of a credit (payment tender, credit memo) to a charge (invoice, debit memo).
@Getter
@Setter
public class AllocationResponse {
    private Long id;
    private Long creditItemId;
    private OpenItemSourceType creditSourceType;
    private String creditReferenceNumber;
    private Long chargeItemId;
    private OpenItemSourceType chargeSourceType;
    private String chargeReferenceNumber;
    private BigDecimal amount;
    private Instant allocationDate;
    // The transaction that applied it.
    private TransactionType sourceType;
    private Long sourceId;
    // True while the paying instrument hasn't cleared.
    private boolean pending;
    private boolean reversed;
    private Instant reversedDate;
    private AllocationReversalReason reversalReason;
}
