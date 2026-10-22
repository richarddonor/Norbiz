package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class AccountingSettingsResponse {
    private Long companyId;
    // False until the company's start date has been set — AR/AP documents can't post before then.
    private boolean configured;
    private Instant startDate;
    private int checkClearingDays;
    private Instant updatedAt;
    private String updatedBy;
}
