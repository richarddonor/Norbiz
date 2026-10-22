package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.OpenItemSourceType;

import java.math.BigDecimal;

// One charge (invoice, memo) a payment paid: how much came from its own tenders and how much from existing credits.
public record PaidDocumentResponse(Long chargeItemId, OpenItemSourceType sourceType, String documentType, String referenceNumber,
                                   BigDecimal paidAmount, BigDecimal creditAmount, BigDecimal amount) {
}
