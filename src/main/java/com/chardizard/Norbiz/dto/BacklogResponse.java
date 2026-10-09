package com.chardizard.Norbiz.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Shared shape of the "documents still waiting on something" dashboard widgets: Pending Outlet Receives,
 * Pending Purchase Orders, Pull-outs Awaiting Receive and Unpaid Purchase Invoices. A field a widget doesn't
 * have (e.g. quantity on invoices) is null, and so are cost-based amounts without VIEW_COST_PRICE.
 */
public record BacklogResponse(
        long documentCount,
        long counterpartyCount,
        BigDecimal outstandingQuantity,
        BigDecimal outstandingAmount,
        // Share of these documents' total quantity already processed (0–100), e.g. partially received. Null when n/a.
        BigDecimal progressPercent,
        // Age in days of the oldest document, as of asOf. Null when nothing is pending.
        Integer oldestAgeDays,
        Instant asOf,
        List<AgingBucket> aging,
        // Top counterparties (outlet/supplier) plus one "Others" slice with a null id.
        List<Slice> byCounterparty,
        // Widget-specific grouping (pull-out reason, payment status, destination warehouse); empty when n/a.
        List<Slice> breakdown,
        List<Document> oldest) {

    public record AgingBucket(String label, int minDays, Integer maxDays, long documentCount,
                              BigDecimal outstandingQuantity, BigDecimal outstandingAmount) {
    }

    public record Slice(Long id, String name, long documentCount, BigDecimal quantity, BigDecimal amount) {
    }

    public record Document(Long id, String referenceNumber, Instant date, int ageDays,
                           Long counterpartyId, String counterpartyName, String group,
                           BigDecimal quantity, BigDecimal outstandingQuantity, BigDecimal outstandingAmount) {
    }
}
