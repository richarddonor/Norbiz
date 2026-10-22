package com.chardizard.Norbiz.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Bank account dropdown option.
@Getter
@AllArgsConstructor
@NoArgsConstructor // for reading cached entries back from Redis
public class BankAccountLookupResponse {
    private Long id;
    private Long companyId;
    private String accountNumber;
    private String name;
    private boolean active;
    private Long bankId;
    private String bankName;
}
