package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
public class BankAccountResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long bankId;
    private String bankCode;
    private String bankName;
    private String name;
    private String accountNumber;
    private BigDecimal openingBalance;
    private Instant openingBalanceDate;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
