package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.CustomerPaymentTenderRepository;
import com.chardizard.Norbiz.repositories.SupplierPaymentTenderRepository;
import com.chardizard.Norbiz.security.CompanyAccess;
import com.chardizard.Norbiz.util.DateRangeUtils;
import com.chardizard.Norbiz.util.SpecificationUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Check lifecycles (docs/ACCOUNTING.md "Check lifecycles").
 * <ul>
 *   <li>Received (customer payment tenders): ON_HAND → DEPOSITED (Deposit) → CLEARED (by hand or Bank Reconciliation),
 *   or BOUNCED from ON_HAND/DEPOSITED — which reverses every application the check funded (the invoices reopen, their
 *   bouncedAmount grows), voids its credit, and books the returned check against the deposit account. A later payment
 *   tender with replacesTenderId covers it (REPLACED once fully covered).</li>
 *   <li>Issued (supplier payment tenders): ISSUED → CLEARED (by hand or Bank Reconciliation), or CANCELLED (stop payment,
 *   stale...) — which reverses what it paid and voids its bank entry.</li>
 * </ul>
 * Clearing by hand only settles the AR/AP side (pending → paid); the bank entry is cleared by a Bank Reconciliation.
 * Every change is recorded as a STATUS_CHANGED event on the payment's history.
 */
@Service
@RequiredArgsConstructor
public class CheckStatusService {

    private static final Logger log = LoggerFactory.getLogger(CheckStatusService.class);

    private final CustomerPaymentTenderRepository customerTenderRepository;
    private final SupplierPaymentTenderRepository supplierTenderRepository;
    private final OpenItemService openItemService;
    private final BankEntryService bankEntryService;
    private final TransactionEventService transactionEventService;
    private final CompanyAccess companyAccess;

    // ---- received checks ----

    /** Received tenders (cash, checks...) across the caller's companies — the checks-on-hand / PDC list. */
    public Page<CustomerPaymentTender> findReceivedForUser(String username, Long customerId, ReceivedPaymentMode mode, ReceivedCheckStatus status,
                                                           Long depositBankAccountId, Map<String, String> filters, Instant checkDateFrom,
                                                           Instant checkDateTo, Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        Specification<CustomerPaymentTender> companyScope = companyIds == null ? null
                : (root, query, cb) -> root.get("customerPayment").get("company").get("id").in(companyIds);
        return customerTenderRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                customerId == null ? null : (root, query, cb) -> cb.equal(root.get("customerPayment").get("customer").get("id"), customerId),
                mode == null ? null : (root, query, cb) -> cb.equal(root.get("mode"), mode),
                status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status),
                depositBankAccountId == null ? null : (root, query, cb) -> cb.equal(root.get("depositBankAccount").get("id"), depositBankAccountId),
                SpecificationUtils.booleanEquals("customerPayment.voided", filters.get("paymentVoided")),
                SpecificationUtils.containsIgnoreCase("checkNumber", filters.get("checkNumber")),
                SpecificationUtils.containsIgnoreCase("customerPayment.referenceNumber", filters.get("paymentReferenceNumber")),
                SpecificationUtils.containsIgnoreCase("customerPayment.customer.name", filters.get("customer")),
                SpecificationUtils.containsIgnoreCase("bank.name", filters.get("bank")),
                SpecificationUtils.dateRange("checkDate", checkDateFrom, checkDateTo)
        ), pageable);
    }

    public CustomerPaymentTender findReceived(Long tenderId, String username) {
        CustomerPaymentTender tender = customerTenderRepository.findById(tenderId)
                .orElseThrow(() -> new IllegalArgumentException("Payment tender not found: " + tenderId));
        companyAccess.assertCompanyAccess(username, tender.getCustomerPayment().getCompany().getId());
        return tender;
    }

    @Transactional
    public List<CustomerPaymentTender> clearReceived(List<Long> tenderIds, String clearedDate, String username) {
        Instant date = DateRangeUtils.startOfDayUtc(clearedDate);
        List<CustomerPaymentTender> tenders = tenderIds.stream().distinct().map(id -> findReceived(id, username)).toList();
        for (CustomerPaymentTender tender : tenders) {
            if (tender.getCustomerPayment().isVoided()) {
                throw new IllegalArgumentException("Payment " + tender.getCustomerPayment().getReferenceNumber() + " is voided");
            }
            if (tender.getMode() != ReceivedPaymentMode.CHECK || tender.getStatus() != ReceivedCheckStatus.DEPOSITED) {
                throw new IllegalArgumentException("Only DEPOSITED checks can be cleared; " + CustomerPaymentService.describe(tender)
                        + " of " + tender.getCustomerPayment().getReferenceNumber() + " is " + tender.getStatus());
            }
            assertNotBefore(date, tender.getStatusDate(), "clearedDate");
            markReceivedCleared(tender, date, null, username);
        }
        return tenders;
    }

    @Transactional
    public CustomerPaymentTender bounce(Long tenderId, String bouncedDate, BounceReason reason, String remarks, String username) {
        CustomerPaymentTender tender = findReceived(tenderId, username);
        CustomerPayment payment = tender.getCustomerPayment();
        if (payment.isVoided()) {
            throw new IllegalArgumentException("Payment " + payment.getReferenceNumber() + " is voided");
        }
        if (tender.getMode() != ReceivedPaymentMode.CHECK) {
            throw new IllegalArgumentException("Only checks can bounce");
        }
        if (tender.getStatus() != ReceivedCheckStatus.ON_HAND && tender.getStatus() != ReceivedCheckStatus.DEPOSITED) {
            throw new IllegalArgumentException("Only ON_HAND or DEPOSITED checks can bounce; " + CustomerPaymentService.describe(tender)
                    + " is " + tender.getStatus() + (tender.getStatus() == ReceivedCheckStatus.CLEARED
                    ? " — book a returned cleared check as a bank reconciliation adjustment and a customer debit memo" : ""));
        }
        Instant date = DateRangeUtils.startOfDayUtc(bouncedDate);
        assertNotBefore(date, tender.getStatusDate(), "bouncedDate");

        if (tender.getStatus() == ReceivedCheckStatus.DEPOSITED) {
            Deposit deposit = tender.getDeposit();
            bankEntryService.post(payment.getCompany(), deposit.getBankAccount(), date, BankEntryType.CHECK_RETURNED, tender.getAmount().negate(),
                    TransactionType.DEPOSIT, deposit.getId(), tender.getId(), deposit.getReferenceNumber(), tender.getCheckNumber(),
                    tender.getCheckDate(), payment.getCustomer().getName() + " — returned " + reason, username);
        }
        openItemService.reverseByCredit(tender.getOpenItem(), AllocationReversalReason.BOUNCED, date, username);
        openItemService.voidItem(tender.getOpenItem(), AllocationReversalReason.BOUNCED, date, username);
        tender.setStatus(ReceivedCheckStatus.BOUNCED);
        tender.setStatusDate(date);
        tender.setBounceReason(reason);
        customerTenderRepository.save(tender);
        recordReceived(tender, "BOUNCED (" + reason + ")" + (remarks != null && !remarks.isBlank() ? ": " + remarks : ""), username);
        log.info("User '{}' bounced {} of customer payment {} ({})", username, CustomerPaymentService.describe(tender), payment.getId(), reason);
        return tender;
    }

    /** Deposited check (or cash) cleared; reconciliation is null when cleared by hand. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markReceivedCleared(CustomerPaymentTender tender, Instant date, BankReconciliation reconciliation, String username) {
        tender.setStatus(ReceivedCheckStatus.CLEARED);
        tender.setStatusDate(date);
        tender.setBankReconciliation(reconciliation);
        openItemService.markCleared(tender.getOpenItem());
        customerTenderRepository.save(tender);
        if (tender.getMode() == ReceivedPaymentMode.CHECK) {
            recordReceived(tender, "CLEARED" + (reconciliation != null ? " (" + reconciliation.getReferenceNumber() + ")" : ""), username);
        }
    }

    /** Undoes a clearing done by a reconciliation that is being voided. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void unmarkReceivedCleared(CustomerPaymentTender tender, String username) {
        tender.setStatus(ReceivedCheckStatus.DEPOSITED);
        tender.setStatusDate(tender.getDeposit().getDepositDate());
        tender.setBankReconciliation(null);
        openItemService.markUncleared(tender.getOpenItem());
        customerTenderRepository.save(tender);
        recordReceived(tender, "back to DEPOSITED (reconciliation voided)", username);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReceived(CustomerPaymentTender tender, String change, String username) {
        CustomerPayment payment = tender.getCustomerPayment();
        transactionEventService.recordSystemEvent(payment.getCompany(), TransactionType.CUSTOMER_PAYMENT, payment.getId(),
                payment.getReferenceNumber(), TransactionEventType.STATUS_CHANGED, username, Instant.now(),
                truncate(capitalize(CustomerPaymentService.describe(tender)) + " " + change));
    }

    // ---- issued checks ----

    /** Issued tenders across the caller's companies — the outstanding-checks list. */
    public Page<SupplierPaymentTender> findIssuedForUser(String username, Long supplierId, IssuedPaymentMode mode, IssuedCheckStatus status,
                                                         Long bankAccountId, Map<String, String> filters, Instant checkDateFrom,
                                                         Instant checkDateTo, Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        Specification<SupplierPaymentTender> companyScope = companyIds == null ? null
                : (root, query, cb) -> root.get("supplierPayment").get("company").get("id").in(companyIds);
        return supplierTenderRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                supplierId == null ? null : (root, query, cb) -> cb.equal(root.get("supplierPayment").get("supplier").get("id"), supplierId),
                mode == null ? null : (root, query, cb) -> cb.equal(root.get("mode"), mode),
                status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status),
                bankAccountId == null ? null : (root, query, cb) -> cb.equal(root.get("bankAccount").get("id"), bankAccountId),
                SpecificationUtils.booleanEquals("supplierPayment.voided", filters.get("paymentVoided")),
                SpecificationUtils.containsIgnoreCase("checkNumber", filters.get("checkNumber")),
                SpecificationUtils.containsIgnoreCase("supplierPayment.referenceNumber", filters.get("paymentReferenceNumber")),
                SpecificationUtils.containsIgnoreCase("supplierPayment.supplier.name", filters.get("supplier")),
                SpecificationUtils.dateRange("checkDate", checkDateFrom, checkDateTo)
        ), pageable);
    }

    public SupplierPaymentTender findIssued(Long tenderId, String username) {
        SupplierPaymentTender tender = supplierTenderRepository.findById(tenderId)
                .orElseThrow(() -> new IllegalArgumentException("Payment tender not found: " + tenderId));
        companyAccess.assertCompanyAccess(username, tender.getSupplierPayment().getCompany().getId());
        return tender;
    }

    @Transactional
    public List<SupplierPaymentTender> clearIssued(List<Long> tenderIds, String clearedDate, String username) {
        Instant date = DateRangeUtils.startOfDayUtc(clearedDate);
        List<SupplierPaymentTender> tenders = tenderIds.stream().distinct().map(id -> findIssued(id, username)).toList();
        for (SupplierPaymentTender tender : tenders) {
            if (tender.getSupplierPayment().isVoided()) {
                throw new IllegalArgumentException("Payment " + tender.getSupplierPayment().getReferenceNumber() + " is voided");
            }
            if (tender.getStatus() != IssuedCheckStatus.ISSUED) {
                throw new IllegalArgumentException("Only ISSUED checks can be cleared; " + SupplierPaymentService.describe(tender)
                        + " of " + tender.getSupplierPayment().getReferenceNumber() + " is " + tender.getStatus());
            }
            assertNotBefore(date, tender.getStatusDate(), "clearedDate");
            markIssuedCleared(tender, date, null, username);
        }
        return tenders;
    }

    @Transactional
    public SupplierPaymentTender cancel(Long tenderId, String cancelledDate, IssuedCancelReason reason, String remarks, String username) {
        SupplierPaymentTender tender = findIssued(tenderId, username);
        SupplierPayment payment = tender.getSupplierPayment();
        if (payment.isVoided()) {
            throw new IllegalArgumentException("Payment " + payment.getReferenceNumber() + " is voided");
        }
        if (tender.getStatus() != IssuedCheckStatus.ISSUED) {
            throw new IllegalArgumentException("Only ISSUED (outstanding) checks can be cancelled; " + SupplierPaymentService.describe(tender)
                    + " is " + tender.getStatus());
        }
        Instant date = DateRangeUtils.startOfDayUtc(cancelledDate);
        assertNotBefore(date, tender.getStatusDate(), "cancelledDate");

        bankEntryService.voidBySourceLine(TransactionType.SUPPLIER_PAYMENT, tender.getId(), username);
        openItemService.reverseByCredit(tender.getOpenItem(), AllocationReversalReason.CANCELLED, date, username);
        openItemService.voidItem(tender.getOpenItem(), AllocationReversalReason.CANCELLED, date, username);
        tender.setStatus(IssuedCheckStatus.CANCELLED);
        tender.setStatusDate(date);
        tender.setCancelReason(reason);
        supplierTenderRepository.save(tender);
        recordIssued(tender, "CANCELLED (" + reason + ")" + (remarks != null && !remarks.isBlank() ? ": " + remarks : ""), username);
        log.info("User '{}' cancelled {} of supplier payment {} ({})", username, SupplierPaymentService.describe(tender), payment.getId(), reason);
        return tender;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markIssuedCleared(SupplierPaymentTender tender, Instant date, BankReconciliation reconciliation, String username) {
        tender.setStatus(IssuedCheckStatus.CLEARED);
        tender.setStatusDate(date);
        tender.setBankReconciliation(reconciliation);
        openItemService.markCleared(tender.getOpenItem());
        supplierTenderRepository.save(tender);
        recordIssued(tender, "CLEARED" + (reconciliation != null ? " (" + reconciliation.getReferenceNumber() + ")" : ""), username);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void unmarkIssuedCleared(SupplierPaymentTender tender, String username) {
        tender.setStatus(IssuedCheckStatus.ISSUED);
        tender.setStatusDate(tender.getSupplierPayment().getPaymentDate());
        tender.setBankReconciliation(null);
        openItemService.markUncleared(tender.getOpenItem());
        supplierTenderRepository.save(tender);
        recordIssued(tender, "back to ISSUED (reconciliation voided)", username);
    }

    private void recordIssued(SupplierPaymentTender tender, String change, String username) {
        SupplierPayment payment = tender.getSupplierPayment();
        transactionEventService.recordSystemEvent(payment.getCompany(), TransactionType.SUPPLIER_PAYMENT, payment.getId(),
                payment.getReferenceNumber(), TransactionEventType.STATUS_CHANGED, username, Instant.now(),
                truncate(capitalize(SupplierPaymentService.describe(tender)) + " " + change));
    }

    private static void assertNotBefore(Instant date, Instant previous, String field) {
        if (previous != null && date.isBefore(previous)) {
            throw new IllegalArgumentException(field + " cannot be before the check's last status date (" + previous.toString().substring(0, 10) + ")");
        }
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String truncate(String s) {
        return s.length() <= 255 ? s : s.substring(0, 255);
    }
}
