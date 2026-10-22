package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.OpenItemSourceType;

import java.math.BigDecimal;

// One existing credit (memo, earlier advance) a payment used, and how much of it.
public record CreditUsedResponse(Long creditItemId, OpenItemSourceType sourceType, String documentType, String referenceNumber,
                                 BigDecimal amount) {
}
