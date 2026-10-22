package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.ApplicationRequest;
import com.chardizard.Norbiz.dto.IssuedTenderRequest;
import com.chardizard.Norbiz.dto.SupplierPaymentRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Supplier Payment (docs/ACCOUNTING.md "Supplier payments"): money we pay a supplier as one or more tenders. Each
 * tender opens a PAYABLE CREDIT; the request's applications pay open payables (Purchase Invoices, supplier credit
 * memos) right away and whatever is left is an advance to the supplier. Our checks stay ISSUED (outstanding, their
 * applications pending) until they clear; a cancelled check reverses what it paid (IssuedCheckService).
 */
@Service
@RequiredArgsConstructor
public class SupplierPaymentService {

    private static final Logger log = LoggerFactory.getLogger(SupplierPaymentService.class);
    private static final TransactionType TYPE = TransactionType.SUPPLIER_PAYMENT;
    private static final String REFERENCE_PREFIX = "SPY";

    private final SupplierPaymentRepository paymentRepository;
    private final SupplierPaymentTenderRepository tenderRepository;
    private final CompanyRepository companyRepository;
    private final SupplierRepository supplierRepository;
    private final BankAccountRepository bankAccountRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;
    private final AccountingSettingsService accountingSettingsService;
    private final OpenItemService openItemService;
    private final BankEntryService bankEntryService;
    private final CompanyAccess companyAccess;

    public Page<SupplierPayment> findAllForUser(String username, Long supplierId, Map<String, String> filters,
                                                Instant dateFrom, Instant dateTo, Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        Specification<SupplierPayment> companyScope = companyIds == null ? null : (root, query, cb) -> root.get("company").get("id").in(companyIds);

        return paymentRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                supplierId == null ? null : (root, query, cb) -> cb.equal(root.get("supplier").get("id"), supplierId),
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.containsIgnoreCase("voucherNumber", filters.get("voucherNumber")),
                SpecificationUtils.containsIgnoreCase("supplier.name", filters.get("supplier")),
                SpecificationUtils.containsIgnoreCase("remarks", filters.get("remarks")),
                SpecificationUtils.anyContainsIgnoreCase(filters.get("checkNumber"), "tenders.checkNumber"),
                SpecificationUtils.booleanEquals("voided", filters.get("voided")),
                SpecificationUtils.dateRange("paymentDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        ), pageable);
    }

    public SupplierPayment findById(Long id, String username) {
        SupplierPayment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Supplier payment not found: " + id));
        companyAccess.assertCompanyAccess(username, payment.getCompany().getId());
        return payment;
    }

    @Transactional
    public SupplierPayment create(SupplierPaymentRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));
        companyAccess.assertCompanyAccess(username, company.getId());

        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new IllegalArgumentException("Supplier not found: " + request.getSupplierId()));
        if (!supplier.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Supplier does not belong to company: " + company.getId());
        }
        if (!supplier.isActive()) {
            throw new IllegalArgumentException("Supplier is inactive: " + supplier.getName());
        }
        Instant paymentDate = DateRangeUtils.startOfDayUtc(request.getPaymentDate());
        accountingSettingsService.assertOnOrAfterStart(company, paymentDate, "Payment");

        Instant now = Instant.now();
        SupplierPayment payment = new SupplierPayment();
        payment.setCompany(company);
        payment.setSupplier(supplier);
        payment.setPaymentDate(paymentDate);
        payment.setVoucherNumber(request.getVoucherNumber());
        payment.setSheetNumber(request.getSheetNumber());
        payment.setRemarks(request.getRemarks());
        payment.setCreatedAt(now);
        payment.setCreatedBy(username);

        BigDecimal total = BigDecimal.ZERO;
        int lineNumber = 1;
        for (IssuedTenderRequest t : request.getTenders()) {
            SupplierPaymentTender tender = buildTender(t, company, supplier, paymentDate);
            tender.setSupplierPayment(payment);
            tender.setLineNumber(lineNumber++);
            payment.getTenders().add(tender);
            total = total.add(t.getAmount());
        }
        payment.setTotalAmount(total);

        List<ApplicationRequest> applications = request.getApplications() != null ? request.getApplications() : List.of();
        List<OpenItem> charges = new ArrayList<>();
        for (ApplicationRequest application : applications) {
            charges.add(openItemService.require(application.getOpenItemId(), company));
        }

        payment.setReferenceNumber(transactionReferenceService.next(company.getId(), TYPE.name(), REFERENCE_PREFIX));
        SupplierPayment saved = paymentRepository.save(payment);
        transactionEventService.recordSystemEvent(company, TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        List<OpenItem> tenderItems = new ArrayList<>();
        for (SupplierPaymentTender tender : saved.getTenders()) {
            OpenItem item = openItemService.open(company, AccountSide.PAYABLE, null, supplier, OpenItemDirection.CREDIT,
                    OpenItemSourceType.SUPPLIER_PAYMENT_TENDER, tender.getId(), tenderReference(saved, tender), paymentDate, null,
                    tender.getAmount(), tender.getStatus() == IssuedCheckStatus.CLEARED, username);
            tender.setOpenItem(item);
            tenderItems.add(item);
            if (tender.getMode() == IssuedPaymentMode.CHECK || tender.getMode() == IssuedPaymentMode.BANK_TRANSFER) {
                bankEntryService.post(company, tender.getBankAccount(), paymentDate,
                        tender.getMode() == IssuedPaymentMode.CHECK ? BankEntryType.CHECK_DISBURSEMENT : BankEntryType.TRANSFER_DISBURSEMENT,
                        tender.getAmount().negate(), TYPE, saved.getId(), tender.getId(), saved.getReferenceNumber(), tender.getCheckNumber(),
                        tender.getCheckDate(), supplier.getName(), username);
            }
        }

        List<Integer> autoOrder = IntStream.range(0, saved.getTenders().size()).boxed()
                .sorted(Comparator.comparing((Integer i) -> saved.getTenders().get(i).getMode() == IssuedPaymentMode.CHECK)
                        .thenComparing(i -> saved.getTenders().get(i).getCheckDate(), Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(i -> i))
                .toList();
        TenderApplications.apply(openItemService, tenderItems, autoOrder, applications, charges, paymentDate, TYPE, saved.getId(), username);

        log.info("User '{}' posted supplier payment (id={}) {} for {} to supplier {} with {} tender(s), {} application(s)", username,
                saved.getId(), saved.getReferenceNumber(), total.toPlainString(), supplier.getId(), saved.getTenders().size(), applications.size());
        return saved;
    }

    /**
     * Cancels a payment posted in error. None of its bank entries may be reconciled yet, and nothing posted later
     * may have applied its credit (Supplier Credit Application, Refund).
     */
    @Transactional
    public SupplierPayment voidPayment(Long id, String username) {
        SupplierPayment payment = findById(id, username);
        if (payment.isVoided()) {
            throw new IllegalArgumentException("Supplier payment already voided: " + id);
        }
        for (SupplierPaymentTender tender : payment.getTenders()) {
            if (openItemService.appliedByOthers(tender.getOpenItem(), TYPE, payment.getId())) {
                throw new IllegalArgumentException("Cannot void " + payment.getReferenceNumber() + ": its " + describe(tender)
                        + " has been applied or refunded by later documents — void those first");
            }
        }
        Instant now = Instant.now();
        bankEntryService.voidBySource(TYPE, payment.getId(), username);
        openItemService.reverseBySource(TYPE, payment.getId(), AllocationReversalReason.VOIDED, payment.getPaymentDate(), username);
        for (SupplierPaymentTender tender : payment.getTenders()) {
            openItemService.voidItem(tender.getOpenItem(), username);
        }
        payment.setVoided(true);
        payment.setVoidedAt(now);
        payment.setVoidedBy(username);
        SupplierPayment saved = paymentRepository.save(payment);
        transactionEventService.recordSystemEvent(saved.getCompany(), TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided supplier payment (id={})", username, id);
        return saved;
    }

    private SupplierPaymentTender buildTender(IssuedTenderRequest t, Company company, Supplier supplier, Instant paymentDate) {
        SupplierPaymentTender tender = new SupplierPaymentTender();
        tender.setMode(t.getMode());
        tender.setAmount(t.getAmount());
        tender.setCheckNumber(t.getCheckNumber());
        tender.setStatusDate(paymentDate);
        boolean drawnOnAccount = t.getMode() == IssuedPaymentMode.CHECK || t.getMode() == IssuedPaymentMode.BANK_TRANSFER;
        if (drawnOnAccount) {
            if (t.getBankAccountId() == null) {
                throw new IllegalArgumentException("A " + t.getMode() + " tender needs bankAccountId (our account it is drawn on)");
            }
            BankAccount account = bankAccountRepository.findById(t.getBankAccountId())
                    .orElseThrow(() -> new IllegalArgumentException("Bank account not found: " + t.getBankAccountId()));
            BankEntryService.assertUsable(account, company);
            tender.setBankAccount(account);
        } else if (t.getBankAccountId() != null) {
            throw new IllegalArgumentException("bankAccountId applies to CHECK/BANK_TRANSFER tenders only");
        }
        if (t.getMode() == IssuedPaymentMode.CHECK) {
            if (!StringUtils.hasText(t.getCheckNumber()) || t.getCheckDate() == null) {
                throw new IllegalArgumentException("A CHECK tender needs checkNumber and checkDate");
            }
            tender.setCheckDate(DateRangeUtils.startOfDayUtc(t.getCheckDate()));
            tender.setStatus(IssuedCheckStatus.ISSUED);
        } else {
            tender.setStatus(IssuedCheckStatus.CLEARED);
        }
        if (t.getReplacesTenderId() != null) {
            SupplierPaymentTender replaced = tenderRepository.findById(t.getReplacesTenderId())
                    .orElseThrow(() -> new IllegalArgumentException("Tender not found: " + t.getReplacesTenderId()));
            if (!replaced.getSupplierPayment().getSupplier().getId().equals(supplier.getId())) {
                throw new IllegalArgumentException("replacesTenderId belongs to a different supplier");
            }
            if (replaced.getStatus() != IssuedCheckStatus.CANCELLED) {
                throw new IllegalArgumentException("Only a CANCELLED check can be replaced; " + describe(replaced) + " is " + replaced.getStatus());
            }
            tender.setReplacesTender(replaced);
        }
        return tender;
    }

    static String tenderReference(SupplierPayment payment, SupplierPaymentTender tender) {
        return payment.getReferenceNumber() + (tender.getMode() == IssuedPaymentMode.CHECK ? " #" + tender.getCheckNumber()
                : payment.getTenders().size() > 1 ? " (" + tender.getMode() + " " + tender.getLineNumber() + ")" : "");
    }

    static String describe(SupplierPaymentTender tender) {
        return tender.getMode() == IssuedPaymentMode.CHECK ? "check #" + tender.getCheckNumber()
                : tender.getMode().name().toLowerCase().replace('_', ' ') + " tender " + tender.getLineNumber();
    }
}
