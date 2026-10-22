package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.OpenItemAllocationRepository;
import com.chardizard.Norbiz.repositories.OpenItemRepository;
import com.chardizard.Norbiz.repositories.PurchaseInvoiceRepository;
import com.chardizard.Norbiz.repositories.SalesInvoiceRepository;
import com.chardizard.Norbiz.util.AccountingAmounts;
import com.chardizard.Norbiz.util.DateRangeUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The only writer of {@link OpenItem} amounts and {@link OpenItemAllocation}s (docs/ACCOUNTING.md "Open items").
 * Every change locks the items it touches (SELECT ... FOR UPDATE, in id order) and re-checks balances under
 * the lock, so two concurrent postings can't over-apply the same invoice or credit. Runs inside the calling
 * transaction service's transaction.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class OpenItemService {

    private static final Logger log = LoggerFactory.getLogger(OpenItemService.class);

    private final OpenItemRepository openItemRepository;
    private final OpenItemAllocationRepository allocationRepository;
    private final PurchaseInvoiceRepository purchaseInvoiceRepository;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final EntityManager entityManager;

    /** Opens a receivable/payable for a posted document (or a payment tender). */
    public OpenItem open(Company company, AccountSide side, Customer customer, Supplier supplier, OpenItemDirection direction,
                         OpenItemSourceType sourceType, Long sourceId, String referenceNumber, Instant documentDate,
                         Instant dueDate, BigDecimal amount, boolean cleared, String username) {
        if ((side == AccountSide.RECEIVABLE) != (customer != null) || (customer == null) == (supplier == null)) {
            throw new IllegalStateException("A " + side + " open item needs exactly its own party");
        }
        if (!AccountingAmounts.isPositive(amount)) {
            throw new IllegalArgumentException(referenceNumber + ": amount must be greater than zero");
        }
        OpenItem item = new OpenItem();
        item.setCompany(company);
        item.setSide(side);
        item.setCustomer(customer);
        item.setSupplier(supplier);
        item.setDirection(direction);
        item.setSourceType(sourceType);
        item.setSourceId(sourceId);
        item.setReferenceNumber(referenceNumber);
        item.setDocumentDate(documentDate);
        item.setDueDate(direction == OpenItemDirection.CHARGE ? dueDate : null);
        item.setAmount(AccountingAmounts.money(amount));
        item.setBalance(item.getAmount());
        item.setCleared(cleared);
        item.setCreatedAt(Instant.now());
        item.setCreatedBy(username);
        OpenItem saved = openItemRepository.save(item);
        log.info("User '{}' opened {} {} open item (id={}) {} for {}", username, side, direction, saved.getId(), referenceNumber, saved.getAmount());
        return saved;
    }

    /**
     * Row-locks the given items (refreshing their state) in id order. Callers that will touch several items
     * should lock them all up front, so every transaction acquires its locks in the same global order.
     */
    public void lock(Collection<OpenItem> items) {
        entityManager.flush();
        items.stream()
                .filter(Objects::nonNull)
                .collect(LinkedHashMap<Long, OpenItem>::new, (m, i) -> m.putIfAbsent(i.getId(), i), Map::putAll)
                .values().stream()
                .sorted(Comparator.comparing(OpenItem::getId))
                .forEach(i -> {
                    if (entityManager.getLockMode(i) != LockModeType.PESSIMISTIC_WRITE) {
                        entityManager.refresh(i, LockModeType.PESSIMISTIC_WRITE);
                    }
                });
    }

    /** Applies {@code amount} of a CREDIT item to a CHARGE item of the same party. */
    public OpenItemAllocation allocate(OpenItem credit, OpenItem charge, BigDecimal amount, Instant date,
                                       TransactionType sourceType, Long sourceId, String username) {
        lock(List.of(credit, charge));
        if (!credit.getCompany().getId().equals(charge.getCompany().getId()) || credit.getSide() != charge.getSide()) {
            throw new IllegalArgumentException("Cannot apply " + credit.getReferenceNumber() + " to " + charge.getReferenceNumber()
                    + ": different company or ledger");
        }
        if (credit.getDirection() != OpenItemDirection.CREDIT) {
            throw new IllegalArgumentException(credit.getReferenceNumber() + " is not a credit that can be applied");
        }
        if (charge.getDirection() != OpenItemDirection.CHARGE) {
            throw new IllegalArgumentException(charge.getReferenceNumber() + " is not an open charge");
        }
        if (!sameParty(credit, charge)) {
            throw new IllegalArgumentException("Cannot apply " + credit.getReferenceNumber() + " to " + charge.getReferenceNumber()
                    + ": they belong to different " + (credit.getSide() == AccountSide.RECEIVABLE ? "customers" : "suppliers"));
        }
        assertOpen(credit);
        assertOpen(charge);
        if (!AccountingAmounts.isPositive(amount) || amount.scale() > 2) {
            throw new IllegalArgumentException("Applied amount must be greater than zero with at most 2 decimals");
        }
        if (amount.compareTo(credit.getBalance()) > 0) {
            throw new IllegalArgumentException("Cannot apply " + amount.toPlainString() + " from " + credit.getReferenceNumber()
                    + ": only " + credit.getBalance().toPlainString() + " is unapplied");
        }
        if (amount.compareTo(charge.getBalance()) > 0) {
            throw new IllegalArgumentException("Cannot apply " + amount.toPlainString() + " to " + charge.getReferenceNumber()
                    + ": its balance is " + charge.getBalance().toPlainString());
        }

        boolean pending = !credit.isCleared();
        credit.setAppliedAmount(credit.getAppliedAmount().add(amount));
        charge.setAppliedAmount(charge.getAppliedAmount().add(amount));
        if (pending) charge.setPendingAmount(charge.getPendingAmount().add(amount));
        refreshBalance(credit);
        refreshBalance(charge);

        OpenItemAllocation allocation = new OpenItemAllocation();
        allocation.setCompany(credit.getCompany());
        allocation.setCreditItem(credit);
        allocation.setChargeItem(charge);
        allocation.setAmount(amount);
        allocation.setAllocationDate(date);
        allocation.setSourceType(sourceType);
        allocation.setSourceId(sourceId);
        allocation.setPending(pending);
        allocation.setCreatedAt(Instant.now());
        allocation.setCreatedBy(username);
        OpenItemAllocation saved = allocationRepository.save(allocation);
        syncPaymentStatus(charge);
        log.info("User '{}' applied {} of {} to {} ({} {})", username, amount.toPlainString(), credit.getReferenceNumber(),
                charge.getReferenceNumber(), sourceType, sourceId);
        return saved;
    }

    /** Reverses every active allocation the given document made. */
    public List<OpenItemAllocation> reverseBySource(TransactionType sourceType, Long sourceId, AllocationReversalReason reason,
                                                    Instant date, String username) {
        List<OpenItemAllocation> active = allocationRepository.findBySourceTypeAndSourceIdOrderByIdAsc(sourceType, sourceId).stream()
                .filter(a -> !a.isReversed()).toList();
        return reverseAll(active, reason, date, username);
    }

    /** Reverses every active allocation funded by the given credit item (its check bounced / was cancelled). */
    public List<OpenItemAllocation> reverseByCredit(OpenItem credit, AllocationReversalReason reason, Instant date, String username) {
        return reverseAll(allocationRepository.findByCreditItemIdAndReversedFalseOrderByIdAsc(credit.getId()), reason, date, username);
    }

    /** Reverses every active allocation paying the given charge item (the refund it represents was cancelled). */
    public List<OpenItemAllocation> reverseByCharge(OpenItem charge, AllocationReversalReason reason, Instant date, String username) {
        return reverseAll(allocationRepository.findByChargeItemIdAndReversedFalseOrderByIdAsc(charge.getId()), reason, date, username);
    }

    private List<OpenItemAllocation> reverseAll(List<OpenItemAllocation> allocations, AllocationReversalReason reason,
                                                Instant date, String username) {
        List<OpenItem> touched = new ArrayList<>();
        allocations.forEach(a -> {
            touched.add(a.getCreditItem());
            touched.add(a.getChargeItem());
        });
        lock(touched);
        Instant now = Instant.now();
        for (OpenItemAllocation a : allocations) {
            OpenItem credit = a.getCreditItem();
            OpenItem charge = a.getChargeItem();
            credit.setAppliedAmount(credit.getAppliedAmount().subtract(a.getAmount()));
            charge.setAppliedAmount(charge.getAppliedAmount().subtract(a.getAmount()));
            if (a.isPending()) charge.setPendingAmount(charge.getPendingAmount().subtract(a.getAmount()));
            if (reason == AllocationReversalReason.BOUNCED) charge.setBouncedAmount(charge.getBouncedAmount().add(a.getAmount()));
            refreshBalance(credit);
            refreshBalance(charge);
            a.setReversed(true);
            a.setReversedDate(date);
            a.setReversedAt(now);
            a.setReversedBy(username);
            a.setReversalReason(reason);
            allocationRepository.save(a);
            syncPaymentStatus(charge);
            log.info("User '{}' reversed allocation {} ({} of {} to {}): {}", username, a.getId(), a.getAmount().toPlainString(),
                    credit.getReferenceNumber(), charge.getReferenceNumber(), reason);
        }
        return allocations;
    }

    /** The credit item's instrument cleared: its allocations stop counting as pending on the charges they pay. */
    public void markCleared(OpenItem credit) {
        setCleared(credit, true);
    }

    /** Undoes {@link #markCleared} (the reconciliation that cleared the instrument was voided). */
    public void markUncleared(OpenItem credit) {
        setCleared(credit, false);
    }

    private void setCleared(OpenItem credit, boolean cleared) {
        if (credit.isCleared() == cleared) return;
        List<OpenItemAllocation> active = allocationRepository.findByCreditItemIdAndReversedFalseOrderByIdAsc(credit.getId());
        List<OpenItem> touched = new ArrayList<>(List.of(credit));
        active.forEach(a -> touched.add(a.getChargeItem()));
        lock(touched);
        credit.setCleared(cleared);
        for (OpenItemAllocation a : active) {
            OpenItem charge = a.getChargeItem();
            charge.setPendingAmount(cleared ? charge.getPendingAmount().subtract(a.getAmount()) : charge.getPendingAmount().add(a.getAmount()));
            a.setPending(!cleared);
            allocationRepository.save(a);
        }
    }

    /** Voids an item whose document was voided (dated today). Its allocations must have been reversed first. */
    public void voidItem(OpenItem item, String username) {
        voidItem(item, AllocationReversalReason.VOIDED, DateRangeUtils.today(), username);
    }

    /** Voids an item: its document was voided, or its check bounced / was cancelled on {@code date}. */
    public void voidItem(OpenItem item, AllocationReversalReason reason, Instant date, String username) {
        lock(List.of(item));
        if (item.getAppliedAmount().signum() != 0) {
            throw new IllegalArgumentException(item.getReferenceNumber() + " still has " + item.getAppliedAmount().toPlainString()
                    + " applied by other documents — void those first");
        }
        item.setStatus(OpenItemStatus.VOIDED);
        item.setVoidedDate(date);
        item.setVoidReason(reason);
        log.info("User '{}' voided open item {} ({}): {}", username, item.getId(), item.getReferenceNumber(), reason);
    }

    /** Loads an open item a request refers to; it must belong to the posting document's company. */
    public OpenItem require(Long id, Company company) {
        OpenItem item = openItemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Open item not found: " + id));
        if (!item.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Open item does not belong to company: " + company.getId());
        }
        return item;
    }

    /** True when documents other than the given one have applied something to/from the item. */
    public boolean appliedByOthers(OpenItem item, TransactionType sourceType, Long sourceId) {
        return allocationRepository.anyActiveFromOtherSource(item.getId(), sourceType, sourceId);
    }

    public void assertOpen(OpenItem item) {
        if (item.getStatus() == OpenItemStatus.VOIDED) {
            throw new IllegalArgumentException(item.getReferenceNumber() + " is voided");
        }
        if (item.getStatus() == OpenItemStatus.CLOSED) {
            throw new IllegalArgumentException(item.getReferenceNumber() + " is already fully applied");
        }
    }

    private static boolean sameParty(OpenItem a, OpenItem b) {
        return a.getSide() == AccountSide.RECEIVABLE
                ? a.getCustomer().getId().equals(b.getCustomer().getId())
                : a.getSupplier().getId().equals(b.getSupplier().getId());
    }

    private static void refreshBalance(OpenItem item) {
        item.setBalance(item.getAmount().subtract(item.getAppliedAmount()));
        if (item.getBalance().signum() < 0 || item.getAppliedAmount().signum() < 0) {
            throw new IllegalStateException("Open item " + item.getId() + " out of range: applied " + item.getAppliedAmount());
        }
        if (item.getStatus() != OpenItemStatus.VOIDED) {
            item.setStatus(item.getBalance().signum() == 0 ? OpenItemStatus.CLOSED : OpenItemStatus.OPEN);
        }
    }

    private void syncPaymentStatus(OpenItem charge) {
        PaymentStatus status = charge.getAppliedAmount().signum() == 0 ? PaymentStatus.UNPAID
                : charge.getBalance().signum() == 0 ? PaymentStatus.PAID
                : PaymentStatus.PARTIALLY_PAID;
        switch (charge.getSourceType()) {
            case PURCHASE_INVOICE -> purchaseInvoiceRepository.findById(charge.getSourceId()).ifPresent(pi -> pi.setPaymentStatus(status));
            case SALES_INVOICE -> salesInvoiceRepository.findById(charge.getSourceId()).ifPresent(si -> si.setPaymentStatus(status));
            default -> { }
        }
    }
}
