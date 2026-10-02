package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.TransactionEventType;
import com.chardizard.Norbiz.models.TransactionType;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class TransactionEventResponse {
    private Long id;
    private TransactionType transactionType;
    private Long transactionId;
    private String referenceNumber;
    private TransactionEventType eventType;
    private Long actionDefinitionId;
    private String actionCode;
    private String actionName;
    private String performedBy;
    private Instant performedAt;
    private String remarks;
}
