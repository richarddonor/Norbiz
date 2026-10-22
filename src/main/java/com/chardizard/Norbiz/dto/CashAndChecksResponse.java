package com.chardizard.Norbiz.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Dashboard widget: where the company's money sits. Bank book balances (opening + non-voided entries up to asOf),
 * received cash and checks not yet deposited (post-dated = check date after asOf), deposits not yet cleared,
 * our issued checks not yet cleared, and checks that bounced in the last bouncedWindowDays.
 */
public record CashAndChecksResponse(
        Instant asOf,
        BigDecimal bookBalance,
        Stat undepositedCash,
        // Every ON_HAND check, post-dated or not.
        Stat checksOnHand,
        // The part of checksOnHand whose check date is still ahead (can't be deposited yet).
        Stat postDatedChecks,
        Stat depositedUncleared,
        Stat issuedOutstanding,
        Stat bounced,
        int bouncedWindowDays,
        List<Account> accounts,
        // ON_HAND checks by check date (oldest first): what to take to the bank next.
        List<Check> toDeposit,
        // Our ISSUED checks, oldest check date first.
        List<Check> outstanding,
        // Bounced in the window, most recent first.
        List<Check> recentlyBounced) {

    public record Stat(long count, BigDecimal amount) {
    }

    /**
     * {@code depositsInTransit} = uncleared money in (positive); {@code outstandingDisbursements} = uncleared money out
     * (negative). Book − both ≈ what the bank statement should show.
     */
    public record Account(Long id, String name, String accountNumber, String bankName, boolean active,
                          BigDecimal bookBalance, BigDecimal depositsInTransit, BigDecimal outstandingDisbursements,
                          Instant lastReconciled) {
    }

    /** {@code daysFromCheckDate} = asOf − check date; negative = post-dated. */
    public record Check(Long tenderId, Long paymentId, String paymentReference, Long partyId, String partyName,
                        String checkNumber, Instant checkDate, Integer daysFromCheckDate, String bankName,
                        BigDecimal amount, String status, Instant statusDate) {
    }
}
