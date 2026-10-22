package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import com.chardizard.Norbiz.security.CompanyAccess;
import com.chardizard.Norbiz.util.DateRangeUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * AR/AP reports computed as of a date from the allocation ledger (docs/ACCOUNTING.md "Reports"): aging, customer/supplier
 * ledgers and the bank book. An item counts as of a date when it is dated on/before it and wasn't voided — or was voided
 * only later because its check bounced/was cancelled (its document being voided removes it from every report). An
 * allocation counts when dated on/before the date and not reversed by then.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountingReportService {

    private static final Logger log = LoggerFactory.getLogger(AccountingReportService.class);
    private static final int ID_CHUNK = 1000;

    /** One item's state as of a date. */
    public record AsOf(OpenItem item, BigDecimal paid, BigDecimal pending, BigDecimal balance) {
    }

    private final OpenItemRepository openItemRepository;
    private final OpenItemAllocationRepository allocationRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final BankAccountRepository bankAccountRepository;
    private final BankEntryRepository bankEntryRepository;
    private final CompanyAccess companyAccess;

    public AgingReportResponse aging(AccountSide side, Long companyId, String asOfDate, AgingBasis basis, Long partyId, boolean detail,
                                     String username) {
        companyAccess.assertCompanyAccess(username, companyId);
        Instant asOf = DateRangeUtils.startOfDayUtc(asOfDate);
        log.debug("User '{}' requested {} aging for company {} as of {}", username, side, companyId, asOfDate);

        Map<Long, AgingRowResponse> rows = new TreeMap<>();
        for (AsOf a : balancesAsOf(side, companyId, partyId, asOf)) {
            OpenItem item = a.item();
            Long party = partyId(item);
            AgingRowResponse row = rows.computeIfAbsent(party, id -> {
                AgingRowResponse r = new AgingRowResponse();
                r.setPartyId(id);
                r.setPartyCode(side == AccountSide.RECEIVABLE ? item.getCustomer().getCode() : item.getSupplier().getCode());
                r.setPartyName(side == AccountSide.RECEIVABLE ? item.getCustomer().getName() : item.getSupplier().getName());
                return r;
            });
            AgingItemResponse line = agingItem(a, asOf, basis);
            add(row, line);
            if (detail) row.getItems().add(line);
        }
        AgingRowResponse totals = new AgingRowResponse();
        for (AgingRowResponse row : rows.values()) {
            totals.setCurrent(totals.getCurrent().add(row.getCurrent()));
            totals.setDays1To30(totals.getDays1To30().add(row.getDays1To30()));
            totals.setDays31To60(totals.getDays31To60().add(row.getDays31To60()));
            totals.setDays61To90(totals.getDays61To90().add(row.getDays61To90()));
            totals.setDays91To120(totals.getDays91To120().add(row.getDays91To120()));
            totals.setOver120(totals.getOver120().add(row.getOver120()));
            totals.setTotalDue(totals.getTotalDue().add(row.getTotalDue()));
            totals.setUnappliedCredits(totals.getUnappliedCredits().add(row.getUnappliedCredits()));
            totals.setNetBalance(totals.getNetBalance().add(row.getNetBalance()));
        }
        AgingReportResponse res = new AgingReportResponse();
        res.setSide(side);
        res.setCompanyId(companyId);
        res.setAsOf(asOf);
        res.setBasis(basis);
        res.setRows(rows.values().stream().sorted(Comparator.comparing(AgingRowResponse::getPartyName, String.CASE_INSENSITIVE_ORDER)).toList());
        res.setTotals(totals);
        return res;
    }

    /** Every item of the side (one party, or all) with a non-zero balance as of the date. */
    public List<AsOf> balancesAsOf(AccountSide side, Long companyId, Long partyId, Instant asOf) {
        Specification<OpenItem> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("company").get("id"), companyId),
                cb.equal(root.get("side"), side),
                cb.lessThanOrEqualTo(root.get("documentDate"), asOf),
                partyId == null ? cb.conjunction()
                        : cb.equal(root.get(side == AccountSide.RECEIVABLE ? "customer" : "supplier").get("id"), partyId));
        List<OpenItem> items = openItemRepository.findAll(spec).stream().filter(i -> standsAt(i, asOf)).toList();
        Map<Long, BigDecimal[]> applied = new HashMap<>();
        List<Long> ids = items.stream().map(OpenItem::getId).toList();
        for (int from = 0; from < ids.size(); from += ID_CHUNK) {
            for (OpenItemAllocation a : allocationRepository.findTouching(ids.subList(from, Math.min(ids.size(), from + ID_CHUNK)))) {
                if (a.getAllocationDate().isAfter(asOf) || (a.isReversed() && !a.getReversedDate().isAfter(asOf))) continue;
                BigDecimal pending = a.isPending() && !a.isReversed() ? a.getAmount() : BigDecimal.ZERO;
                accumulate(applied, a.getCreditItem().getId(), a.getAmount(), BigDecimal.ZERO);
                accumulate(applied, a.getChargeItem().getId(), a.getAmount(), pending);
            }
        }
        List<AsOf> result = new ArrayList<>();
        for (OpenItem item : items) {
            BigDecimal[] paid = applied.getOrDefault(item.getId(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal balance = item.getAmount().subtract(paid[0]);
            if (balance.signum() != 0) result.add(new AsOf(item, paid[0], paid[1], balance));
        }
        result.sort(Comparator.comparing((AsOf a) -> a.item().getDocumentDate()).thenComparing(a -> a.item().getId()));
        return result;
    }

    public LedgerResponse ledger(AccountSide side, Long companyId, Long partyId, String dateFrom, String dateTo, String username) {
        companyAccess.assertCompanyAccess(username, companyId);
        String partyName;
        if (side == AccountSide.RECEIVABLE) {
            Customer c = customerRepository.findById(partyId).orElseThrow(() -> new IllegalArgumentException("Customer not found: " + partyId));
            if (!c.getCompany().getId().equals(companyId)) throw new IllegalArgumentException("Customer does not belong to company: " + companyId);
            partyName = c.getName();
        } else {
            Supplier s = supplierRepository.findById(partyId).orElseThrow(() -> new IllegalArgumentException("Supplier not found: " + partyId));
            if (!s.getCompany().getId().equals(companyId)) throw new IllegalArgumentException("Supplier does not belong to company: " + companyId);
            partyName = s.getName();
        }
        Instant from = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant to = DateRangeUtils.startOfDayUtc(dateTo);
        if (from.isAfter(to)) throw new IllegalArgumentException("dateFrom is after dateTo");

        List<OpenItem> items = openItemRepository.findAll((root, query, cb) -> cb.and(
                cb.equal(root.get("company").get("id"), companyId),
                cb.equal(root.get("side"), side),
                cb.equal(root.get(side == AccountSide.RECEIVABLE ? "customer" : "supplier").get("id"), partyId),
                cb.lessThanOrEqualTo(root.get("documentDate"), to)));

        BigDecimal opening = BigDecimal.ZERO;
        List<LedgerLineResponse> lines = new ArrayList<>();
        for (OpenItem item : items) {
            if (item.getStatus() == OpenItemStatus.VOIDED && item.getVoidReason() == AllocationReversalReason.VOIDED) continue;
            BigDecimal signed = item.getDirection() == OpenItemDirection.CHARGE ? item.getAmount() : item.getAmount().negate();
            if (item.getDocumentDate().isBefore(from)) opening = opening.add(signed);
            else lines.add(ledgerLine(item, item.getDocumentDate(), signed, describe(item)));
            if (item.getStatus() == OpenItemStatus.VOIDED && !item.getVoidedDate().isAfter(to)) {
                String reversal = item.getVoidReason() == AllocationReversalReason.BOUNCED ? "Check bounced" : "Check cancelled";
                if (item.getVoidedDate().isBefore(from)) opening = opening.subtract(signed);
                else lines.add(ledgerLine(item, item.getVoidedDate(), signed.negate(), reversal));
            }
        }
        lines.sort(Comparator.comparing(LedgerLineResponse::getDate).thenComparing(LedgerLineResponse::getOpenItemId));
        BigDecimal running = opening;
        for (LedgerLineResponse line : lines) {
            running = running.add(line.getDebit() != null ? line.getDebit() : line.getCredit().negate());
            line.setBalance(running);
        }
        LedgerResponse res = new LedgerResponse();
        res.setSide(side);
        res.setCompanyId(companyId);
        res.setPartyId(partyId);
        res.setPartyName(partyName);
        res.setDateFrom(from);
        res.setDateTo(to);
        res.setOpeningBalance(opening);
        res.setClosingBalance(running);
        res.setLines(lines);
        return res;
    }

    public BankBookResponse bankBook(Long bankAccountId, String dateFrom, String dateTo, String username) {
        BankAccount account = bankAccountRepository.findById(bankAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Bank account not found: " + bankAccountId));
        companyAccess.assertCompanyAccess(username, account.getCompany().getId());
        Instant from = DateRangeUtils.startOfDayUtc(dateFrom);
        Instant to = DateRangeUtils.startOfDayUtc(dateTo);
        if (from.isAfter(to)) throw new IllegalArgumentException("dateFrom is after dateTo");

        BigDecimal opening = account.getOpeningBalance().add(bankEntryRepository.sumBefore(account.getId(), from));
        BigDecimal running = opening;
        List<BankEntryResponse> entries = new ArrayList<>();
        for (BankEntry e : bankEntryRepository.findBetween(account.getId(), from, to)) {
            running = running.add(e.getAmount());
            BankEntryResponse line = AccountingResponses.bankEntry(e);
            line.setRunningBalance(running);
            entries.add(line);
        }
        BankBookResponse res = new BankBookResponse();
        res.setBankAccountId(account.getId());
        res.setBankAccountName(account.getName());
        res.setAccountNumber(account.getAccountNumber());
        res.setDateFrom(from);
        res.setDateTo(to);
        res.setOpeningBalance(opening);
        res.setClosingBalance(running);
        res.setEntries(entries);
        return res;
    }

    /** Days past due (DUE_DATE) or since the document date (DOCUMENT_DATE); never negative. */
    public static int daysOverdue(OpenItem item, Instant asOf, AgingBasis basis) {
        Instant from = basis == AgingBasis.DOCUMENT_DATE || item.getDueDate() == null ? item.getDocumentDate() : item.getDueDate();
        return (int) Math.max(0, ChronoUnit.DAYS.between(from, asOf));
    }

    public static String bucket(int daysOverdue) {
        if (daysOverdue <= 0) return "CURRENT";
        if (daysOverdue <= 30) return "DAYS_1_30";
        if (daysOverdue <= 60) return "DAYS_31_60";
        if (daysOverdue <= 90) return "DAYS_61_90";
        if (daysOverdue <= 120) return "DAYS_91_120";
        return "OVER_120";
    }

    private static boolean standsAt(OpenItem item, Instant asOf) {
        if (item.getStatus() != OpenItemStatus.VOIDED) return true;
        return item.getVoidReason() != AllocationReversalReason.VOIDED && item.getVoidedDate().isAfter(asOf);
    }

    private static AgingItemResponse agingItem(AsOf a, Instant asOf, AgingBasis basis) {
        OpenItem item = a.item();
        AgingItemResponse line = new AgingItemResponse();
        line.setOpenItemId(item.getId());
        line.setSourceType(item.getSourceType());
        line.setSourceId(item.getSourceId());
        line.setDirection(item.getDirection());
        line.setReferenceNumber(item.getReferenceNumber());
        line.setDocumentDate(item.getDocumentDate());
        line.setDueDate(item.getDueDate());
        line.setAmount(item.getAmount());
        line.setPaidAmount(a.paid());
        line.setPendingAmount(a.pending());
        line.setBalance(a.balance());
        if (item.getDirection() == OpenItemDirection.CHARGE) {
            line.setDaysOverdue(daysOverdue(item, asOf, basis));
            line.setBucket(bucket(line.getDaysOverdue()));
        } else {
            line.setBucket("UNAPPLIED_CREDIT");
        }
        return line;
    }

    private static void add(AgingRowResponse row, AgingItemResponse line) {
        BigDecimal b = line.getBalance();
        switch (line.getBucket()) {
            case "CURRENT" -> row.setCurrent(row.getCurrent().add(b));
            case "DAYS_1_30" -> row.setDays1To30(row.getDays1To30().add(b));
            case "DAYS_31_60" -> row.setDays31To60(row.getDays31To60().add(b));
            case "DAYS_61_90" -> row.setDays61To90(row.getDays61To90().add(b));
            case "DAYS_91_120" -> row.setDays91To120(row.getDays91To120().add(b));
            case "OVER_120" -> row.setOver120(row.getOver120().add(b));
            default -> row.setUnappliedCredits(row.getUnappliedCredits().add(b));
        }
        if (!"UNAPPLIED_CREDIT".equals(line.getBucket())) row.setTotalDue(row.getTotalDue().add(b));
        row.setNetBalance(row.getTotalDue().subtract(row.getUnappliedCredits()));
    }

    private static void accumulate(Map<Long, BigDecimal[]> applied, Long itemId, BigDecimal amount, BigDecimal pending) {
        BigDecimal[] sums = applied.computeIfAbsent(itemId, id -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        sums[0] = sums[0].add(amount);
        sums[1] = sums[1].add(pending);
    }

    private static Long partyId(OpenItem item) {
        return item.getSide() == AccountSide.RECEIVABLE ? item.getCustomer().getId() : item.getSupplier().getId();
    }

    private static LedgerLineResponse ledgerLine(OpenItem item, Instant date, BigDecimal signed, String description) {
        LedgerLineResponse line = new LedgerLineResponse();
        line.setDate(date);
        line.setOpenItemId(item.getId());
        line.setSourceType(item.getSourceType());
        line.setSourceId(item.getSourceId());
        line.setReferenceNumber(item.getReferenceNumber());
        line.setDescription(description);
        if (signed.signum() >= 0) line.setDebit(signed);
        else line.setCredit(signed.negate());
        return line;
    }

    private static String describe(OpenItem item) {
        return switch (item.getSourceType()) {
            case SALES_INVOICE -> "Sales invoice";
            case PURCHASE_INVOICE -> "Purchase invoice";
            case CUSTOMER_DEBIT_MEMO, SUPPLIER_CREDIT_MEMO -> "Debit to account (memo)";
            case CUSTOMER_CREDIT_MEMO, SUPPLIER_DEBIT_MEMO -> "Credit to account (memo)";
            case CUSTOMER_PAYMENT_TENDER, SUPPLIER_PAYMENT_TENDER -> "Payment";
            case CUSTOMER_REFUND, SUPPLIER_REFUND -> "Refund";
        };
    }
}
