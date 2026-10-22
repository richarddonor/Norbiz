package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.ApplicationRequest;
import com.chardizard.Norbiz.models.OpenItem;
import com.chardizard.Norbiz.models.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a payment's applications across its tenders (customer and supplier payments). An application naming a
 * tenderIndex draws from that tender only; the others draw from the tenders in {@code autoOrder} (settled tenders
 * first, then checks by check date), spilling over to the next tender when one runs out.
 */
final class TenderApplications {

    private TenderApplications() {
    }

    static void apply(OpenItemService openItemService, List<OpenItem> tenderItems, List<Integer> autoOrder, List<ApplicationRequest> applications,
                      List<OpenItem> charges, Instant date, TransactionType sourceType, Long sourceId, String username) {
        BigDecimal tendered = tenderItems.stream().map(OpenItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal applied = applications.stream().map(ApplicationRequest::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (applied.compareTo(tendered) > 0) {
            throw new IllegalArgumentException("Applications (" + applied.toPlainString() + ") exceed the amount paid (" + tendered.toPlainString() + ")");
        }
        List<OpenItem> toLock = new ArrayList<>(tenderItems);
        toLock.addAll(charges);
        openItemService.lock(toLock);

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
