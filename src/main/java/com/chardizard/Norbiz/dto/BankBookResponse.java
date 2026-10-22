package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class BankBookResponse {
    private Long bankAccountId;
    private String bankAccountName;
    private String accountNumber;
    private Instant dateFrom;
    private Instant dateTo;
    private BigDecimal openingBalance;
    private BigDecimal closingBalance;
    private List<BankEntryResponse> entries;
}
