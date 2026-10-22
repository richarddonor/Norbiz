package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.AccountSide;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// A customer's (what they owe us) or supplier's (what we owe them) running balance over a period.
@Getter
@Setter
public class LedgerResponse {
    private AccountSide side;
    private Long companyId;
    private Long partyId;
    private String partyName;
    private Instant dateFrom;
    private Instant dateTo;
    private BigDecimal openingBalance;
    private BigDecimal closingBalance;
    private List<LedgerLineResponse> lines;
}
