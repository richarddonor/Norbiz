package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.ApplicationRequest;
import com.chardizard.Norbiz.dto.PaymentCreditRequest;
import com.chardizard.Norbiz.models.AccountSide;
import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.OpenItem;
import com.chardizard.Norbiz.models.OpenItemDirection;
import com.chardizard.Norbiz.models.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a payment's applications across its tenders (customer and supplier payments). An application naming a
 * tenderIndex draws from that tender only; the others draw from the tenders in {@code autoOrder} (settled tenders
 * first, then checks by check date), spilling over to the next tender when one runs out.
 *
 * A payment can also use the party's existing credits (supplier debit memos, customer credit memos, earlier advances)
 * on the same posting. Those are applied before the tenders, recorded under the payment, and reversed when it is voided.
 */
final class TenderApplications {

    private TenderApplications() {
    }

    /** An existing credit the payment applies to a charge, alongside its own tenders. */
    record CreditUse(OpenItem credit, OpenItem charge, BigDecimal amount) {
    }

    /** Loads the request's credit lines; both items must be open items of the paying party, on its side of the ledger. */
    static List<CreditUse> resolveCredits(OpenItemService openItemService, List<PaymentCreditRequest> lines, Company company,
                                          AccountSide side, Long partyId, String partyName) {
        if (lines == null) return List.of();
        List<CreditUse> uses = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            PaymentCreditRequest line = lines.get(i);
            OpenItem credit = openItemService.require(line.getCreditItemId(), company);
            OpenItem charge = openItemService.require(line.getChargeItemId(), company);
            if (!belongsTo(credit, side, partyId) || !belongsTo(charge, side, partyId)) {
                throw new IllegalArgumentException("Credit line " + (i + 1) + ": both items must be "
                        + (side == AccountSide.RECEIVABLE ? "receivables of customer " : "payables of supplier ") + partyName);
            }
            if (credit.getDirection() != OpenItemDirection.CREDIT) {
                throw new IllegalArgumentException("Credit line " + (i + 1) + ": " + credit.getReferenceNumber() + " is not a credit that can be applied");
            }
            if (charge.getDirection() != OpenItemDirection.CHARGE) {
                throw new IllegalArgumentException("Credit line " + (i + 1) + ": " + charge.getReferenceNumber() + " is not an open charge");
            }
            uses.add(new CreditUse(credit, charge, line.getAmount()));
        }
        return uses;
    }

    private static boolean belongsTo(OpenItem item, AccountSide side, Long partyId) {
        if (item.getSide() != side) return false;
        Long itemPartyId = side == AccountSide.RECEIVABLE
                ? (item.getCustomer() != null ? item.getCustomer().getId() : null)
                : (item.getSupplier() != null ? item.getSupplier().getId() : null);
        return partyId.equals(itemPartyId);
    }

    static void apply(OpenItemService openItemService, List<OpenItem> tenderItems, List<Integer> autoOrder, List<ApplicationRequest> applications,
                      List<OpenItem> charges, List<CreditUse> credits, Instant date, TransactionType sourceType, Long sourceId, String username) {
        BigDecimal tendered = tenderItems.stream().map(OpenItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal applied = applications.stream().map(ApplicationRequest::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (applied.compareTo(tendered) > 0) {
            throw new IllegalArgumentException("Applications (" + applied.toPlainString() + ") exceed the amount paid (" + tendered.toPlainString() + ")");
        }
        List<OpenItem> toLock = new ArrayList<>(tenderItems);
        toLock.addAll(charges);
        credits.forEach(c -> {
            toLock.add(c.credit());
            toLock.add(c.charge());
        });
        openItemService.lock(toLock);

        for (CreditUse use : credits) {
            openItemService.allocate(use.credit(), use.charge(), use.amount(), date, sourceType, sourceId, username);
        }

        for (int i = 0; i < applications.size(); i++) {
            ApplicationRequest application = applications.get(i);
            OpenItem charge = charges.get(i);
            if (application.getTenderIndex() != null) {
                if (application.getTenderIndex() >= tenderItems.size()) {
                    throw new IllegalArgumentException("tenderIndex " + application.getTenderIndex() + " is out of range");
                }
                openItemService.allocate(tenderItems.get(application.getTenderIndex()), charge, application.getAmount(), date,
                        sourceType, sourceId, username);
                continue;
            }
            BigDecimal remaining = application.getAmount();
            for (int index : autoOrder) {
                if (remaining.signum() == 0) break;
                OpenItem tender = tenderItems.get(index);
                BigDecimal take = remaining.min(tender.getBalance());
                if (take.signum() <= 0) continue;
                openItemService.allocate(tender, charge, take, date, sourceType, sourceId, username);
                remaining = remaining.subtract(take);
            }
            if (remaining.signum() > 0) {
                throw new IllegalArgumentException("Not enough unapplied payment left to apply " + application.getAmount().toPlainString()
                        + " to " + charge.getReferenceNumber());
            }
        }
    }
}
