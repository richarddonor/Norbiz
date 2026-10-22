package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

// One customer/supplier (or the grand total, with no party) of an aging report.
@Getter
@Setter
public class AgingRowResponse {
    private Long partyId;
    private String partyCode;
    private String partyName;
    private BigDecimal current = BigDecimal.ZERO;
    private BigDecimal days1To30 = BigDecimal.ZERO;
    private BigDecimal days31To60 = BigDecimal.ZERO;
    private BigDecimal days61To90 = BigDecimal.ZERO;
    private BigDecimal days91To120 = BigDecimal.ZERO;
    private BigDecimal over120 = BigDecimal.ZERO;
    // Sum of the buckets: open charges.
    private BigDecimal totalDue = BigDecimal.ZERO;
    // Advances and credit memos not yet applied (positive).
    private BigDecimal unappliedCredits = BigDecimal.ZERO;
    // totalDue - unappliedCredits.
    private BigDecimal netBalance = BigDecimal.ZERO;
    // detail=true only.
    private List<AgingItemResponse> items = new ArrayList<>();
}
