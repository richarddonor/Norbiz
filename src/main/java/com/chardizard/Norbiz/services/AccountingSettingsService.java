package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.AccountingSettingsRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.AccountingSettingsRepository;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.OpenItemRepository;
import com.chardizard.Norbiz.repositories.PurchaseInvoiceRepository;
import com.chardizard.Norbiz.security.CompanyAccess;
import com.chardizard.Norbiz.util.AccountingAmounts;
import com.chardizard.Norbiz.util.DateRangeUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Per-company AR/AP settings, chiefly the accounting start date (docs/ACCOUNTING.md "Start date"): invoices dated
 * on/after it are tracked as open items; earlier balances come in as OPENING_BALANCE memos. Also decides whether
 * a Purchase Invoice is tracked, since that depends on the start date.
 */
@Service
@RequiredArgsConstructor
public class AccountingSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AccountingSettingsService.class);

    private final AccountingSettingsRepository settingsRepository;
    private final CompanyRepository companyRepository;
    private final OpenItemRepository openItemRepository;
    private final PurchaseInvoiceRepository purchaseInvoiceRepository;
    private final OpenItemService openItemService;
    private final CompanyAccess companyAccess;

    public Optional<AccountingSettings> find(Long companyId, String username) {
        companyAccess.assertCompanyAccess(username, companyId);
        return settingsRepository.findByCompanyId(companyId);
    }

    /**
     * Creates or updates the company's settings. The start date can only move while nothing has been posted
     * against the subledger (no allocation, no open item other than tracked Purchase Invoices); moving it
     * re-decides which Purchase Invoices are tracked.
     */
    @Transactional
    public AccountingSettings save(AccountingSettingsRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));
        companyAccess.assertCompanyAccess(username, company.getId());

        Instant startDate = DateRangeUtils.startOfDayUtc(request.getStartDate());
        if (startDate == null) {
            throw new IllegalArgumentException("startDate is required (yyyy-MM-dd)");
        }

        AccountingSettings settings = settingsRepository.findByCompanyId(company.getId()).orElse(null);
        boolean startDateChanged = settings == null || !settings.getStartDate().equals(startDate);
        if (settings != null && startDateChanged
                && (openItemRepository.anyAllocationForCompany(company.getId())
                    || openItemRepository.anyForCompanyExcept(company.getId(), OpenItemSourceType.PURCHASE_INVOICE))) {
            throw new IllegalArgumentException("The accounting start date can no longer be changed: receivables/payables have already been "
                    + "posted against it");
        }
        if (settings == null) {
            settings = new AccountingSettings();
            settings.setCompany(company);
        }
        settings.setStartDate(startDate);
        if (request.getCheckClearingDays() != null) settings.setCheckClearingDays(request.getCheckClearingDays());
        AccountingSettings saved = settingsRepository.save(settings);

        if (startDateChanged) retrackPurchaseInvoices(company, startDate, username);
        log.info("User '{}' saved accounting settings for company {} (start date {})", username, company.getId(), request.getStartDate());
        return saved;
    }

    /** The company's accounting start date; AR/AP documents can't post until it is configured. */
    public Instant requireStartDate(Company company) {
        return settingsRepository.findByCompanyId(company.getId())
                .map(AccountingSettings::getStartDate)
                .orElseThrow(() -> new IllegalArgumentException("Accounting start date is not configured for this company "
                        + "(PUT /accounting/settings)"));
    }

    /** Rejects a document dated before the start date (only OPENING_BALANCE memos may be). */
    public void assertOnOrAfterStart(Company company, Instant date, String what) {
        Instant start = requireStartDate(company);
        if (date.isBefore(start)) {
            throw new IllegalArgumentException(what + " date is before the accounting start date ("
                    + start.toString().substring(0, 10) + "); enter older balances as OPENING_BALANCE memos");
        }
    }

    /**
     * Opens the invoice's PAYABLE CHARGE when it is tracked: NATIVE, not voided, dated on/after the start date,
     * with a positive net amount. Otherwise it stays untracked (paymentStatus is left as it is).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void trackPurchaseInvoice(PurchaseInvoice invoice, String username) {
        if (invoice.getOpenItem() != null || invoice.isVoided() || invoice.getOrigin() != TransactionOrigin.NATIVE) return;
        Optional<Instant> start = settingsRepository.findByCompanyId(invoice.getCompany().getId()).map(AccountingSettings::getStartDate);
        if (start.isEmpty() || invoice.getInvoiceDate().isBefore(start.get()) || !AccountingAmounts.isPositive(invoice.getNetAmount())) return;
        Instant dueDate = invoice.getDueDate() != null ? invoice.getDueDate() : invoice.getInvoiceDate();
        OpenItem item = openItemService.open(invoice.getCompany(), AccountSide.PAYABLE, null, invoice.getSupplier(), OpenItemDirection.CHARGE,
                OpenItemSourceType.PURCHASE_INVOICE, invoice.getId(), invoice.getReferenceNumber(), invoice.getInvoiceDate(), dueDate,
                invoice.getNetAmount(), true, username);
        invoice.setOpenItem(item);
        invoice.setPaymentStatus(PaymentStatus.UNPAID);
    }

    private void retrackPurchaseInvoices(Company company, Instant startDate, String username) {
        for (PurchaseInvoice pi : purchaseInvoiceRepository.findByCompanyIdAndOpenItemIsNotNull(company.getId())) {
            OpenItem item = pi.getOpenItem();
            pi.setOpenItem(null);
            purchaseInvoiceRepository.saveAndFlush(pi);
            openItemRepository.delete(item);
        }
        int tracked = 0;
        for (PurchaseInvoice pi : purchaseInvoiceRepository.findByCompanyIdAndOriginAndVoidedFalseAndInvoiceDateGreaterThanEqual(
                company.getId(), TransactionOrigin.NATIVE, startDate)) {
            trackPurchaseInvoice(pi, username);
            if (pi.getOpenItem() != null) tracked++;
        }
        log.info("User '{}' set accounting start date for company {}: {} purchase invoice(s) now tracked", username, company.getId(), tracked);
    }
}
