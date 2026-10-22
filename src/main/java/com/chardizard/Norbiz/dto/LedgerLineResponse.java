package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.OpenItemSourceType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

// One movement of a customer/supplier balance: a charge (debit) or a credit, or a bounced/cancelled check's credit reversed.
@Getter
@Setter
public class LedgerLineResponse {
    private Instant date;
    private Long openItemId;
    private OpenItemSourceType sourceType;
    private Long sourceId;
    private String referenceNumber;
    private String description;
    // Raises the balance (invoice, debit memo, refund paid out, reversed check).
    private BigDecimal debit;
    // Lowers it (payment tender, credit memo).
    private BigDecimal credit;
    private BigDecimal balance;
}
