package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// Applies part of the posting document's credit to one open charge (an invoice, debit memo...) of the same party.
@Getter
@Setter
public class ApplicationRequest {

    // The CHARGE open item to pay (see GET /lookups/open-items).
    @NotNull
    private Long openItemId;

    @NotNull
    @DecimalMin(value = "0.01")
    @Digits(integer = 17, fraction = 2)
    private BigDecimal amount;

    // Payments only: 0-based index of the tender (in the request's tenders list) to apply from. Omitted = taken from
    // the tenders in order: settled ones (cash, transfer, card...) first, then checks by check date.
    @Min(0)
    private Integer tenderIndex;
}
