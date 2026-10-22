package com.chardizard.Norbiz.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Shared shape of the Receivables Aging and Payables Due dashboard widgets: the OPEN items of one side of the AR/AP
 * subledger as of today. "Days overdue" is asOf − due date (document date when there is none); negative = not yet due.
 * Receivables bucket by days overdue (Current, 1-30 … 120+, like the AR aging report); payables bucket by when
 * they fall due (overdue, this week, this month, later).
 */
public record SubledgerSummaryResponse(
        String side,
        Instant asOf,
        // Balance left on open charges (invoices, debit memos, refunds).
        BigDecimal outstanding,
        BigDecimal overdue,
        // Not yet overdue but due within DUE_SOON_DAYS days.
        BigDecimal dueSoon,
        int dueSoonDays,
        // Part of what's been applied to these charges that is paid by checks not yet cleared (legacy "PDC").
        BigDecimal pendingClearance,
        // Open credits (advances, credit memos) not yet applied to any charge.
        BigDecimal unappliedCredits,
        long chargeCount,
        long overdueCount,
        long partyCount,
        Integer oldestOverdueDays,
        int periodDays,
        // Billed (invoices + charge memos) and settled (money received/paid) over the last periodDays.
        BigDecimal billed,
        BigDecimal settled,
        List<Bucket> buckets,
        // Top parties by outstanding balance, plus one "Others" row with a null id.
        List<Party> byParty,
        // Charges in due-date order (most overdue first).
        List<Item> items) {

    /** Days overdue in [minDays, maxDays]; a null bound is open-ended. */
    public record Bucket(String key, String label, Integer minDays, Integer maxDays, long count, BigDecimal amount) {
    }

    public record Party(Long id, String name, long chargeCount, BigDecimal outstanding, BigDecimal overdue,
                        BigDecimal unappliedCredits) {
    }

    public record Item(Long openItemId, String sourceType, Long sourceId, String referenceNumber, Instant documentDate,
                       Instant dueDate, int daysOverdue, Long partyId, String partyName, BigDecimal amount,
                       BigDecimal balance, BigDecimal pending) {
    }
}
