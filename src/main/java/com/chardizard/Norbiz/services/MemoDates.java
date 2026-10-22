package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.util.AccountingAmounts;
import com.chardizard.Norbiz.util.DateRangeUtils;

import java.time.Instant;

// Date rules shared by customer and supplier memos (docs/ACCOUNTING.md "Start date").
final class MemoDates {

    private MemoDates() {
    }

    /** OPENING_BALANCE memos carry balances from before the start date, so they must be dated before it; all others on/after. */
    static void assertPlacement(Instant startDate, Instant memoDate, boolean openingBalance) {
        if (openingBalance && !memoDate.isBefore(startDate)) {
            throw new IllegalArgumentException("An OPENING_BALANCE memo must be dated before the accounting start date ("
                    + startDate.toString().substring(0, 10) + ")");
        }
        if (!openingBalance && memoDate.isBefore(startDate)) {
            throw new IllegalArgumentException("Memo date is before the accounting start date (" + startDate.toString().substring(0, 10)
                    + "); use reason OPENING_BALANCE for balances carried over from before it");
        }
    }

    /** Requested due date, else memoDate + termDays. An opening balance may be past due already. */
    static Instant dueDate(Instant memoDate, int termDays, String requestedDueDate, boolean openingBalance) {
        Instant dueDate = DateRangeUtils.startOfDayUtc(requestedDueDate);
        if (dueDate == null) return AccountingAmounts.dueDate(memoDate, termDays);
        if (!openingBalance && dueDate.isBefore(memoDate)) {
            throw new IllegalArgumentException("dueDate cannot be before memoDate");
        }
        return dueDate;
    }
}
