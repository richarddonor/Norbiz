package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.CreditApplicationLineRequest;
import com.chardizard.Norbiz.dto.SupplierCreditApplicationRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.SupplierCreditApplicationRepository;
import com.chardizard.Norbiz.repositories.SupplierRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Supplier Credit Application (docs/ACCOUNTING.md "Credit applications"): applies credits posted earlier — the
 * unapplied part of payment tenders (advances to the supplier) and supplier debit memos — to the supplier's open payables (Purchase Invoices, supplier credit memos).
 */
@Service
@RequiredArgsConstructor
public class SupplierCreditApplicationService {

    private static final Logger log = LoggerFactory.getLogger(SupplierCreditApplicationService.class);
    private static final TransactionType TYPE = TransactionType.SUPPLIER_CREDIT_APPLICATION;
    private static final String REFERENCE_PREFIX = "SCA";

    private final SupplierCreditApplicationRepository applicationRepository;
    private final CompanyRepository companyRepository;
    private final SupplierRepository supplierRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;
    private final AccountingSettingsService accountingSettingsService;
    private final OpenItemService openItemService;
    private final CompanyAccess companyAccess;

    public Page<SupplierCreditApplication> findAllForUser(String username, Long supplierId, Map<String, String> filters,
                                                          Instant dateFrom, Instant dateTo, Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        Specification<SupplierCreditApplication> companyScope = companyIds == null ? null
                : (root, query, cb) -> root.get("company").get("id").in(companyIds);

        return applicationRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                supplierId == null ? null : (root, query, cb) -> cb.equal(root.get("supplier").get("id"), supplierId),
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.containsIgnoreCase("supplier.name", filters.get("supplier")),
                SpecificationUtils.containsIgnoreCase("remarks", filters.get("remarks")),
                SpecificationUtils.booleanEquals("voided", filters.get("voided")),
                SpecificationUtils.dateRange("applicationDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        ), pageable);
    }

    public SupplierCreditApplication findById(Long id, String username) {
        SupplierCreditApplication application = applicationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Supplier credit application not found: " + id));
        companyAccess.assertCompanyAccess(username, application.getCompany().getId());
        return application;
    }

    @Transactional
    public SupplierCreditApplication create(SupplierCreditApplicationRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));
        companyAccess.assertCompanyAccess(username, company.getId());
        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new IllegalArgumentException("Supplier not found: " + request.getSupplierId()));
        if (!supplier.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Supplier does not belong to company: " + company.getId());
        }
        Instant applicationDate = DateRangeUtils.startOfDayUtc(request.getApplicationDate());
        accountingSettingsService.assertOnOrAfterStart(company, applicationDate, "Application");

        Instant now = Instant.now();
        SupplierCreditApplication application = new SupplierCreditApplication();
        application.setCompany(company);
        application.setSupplier(supplier);
        application.setApplicationDate(applicationDate);
        application.setSheetNumber(request.getSheetNumber());
        application.setRemarks(request.getRemarks());
        application.setCreatedAt(now);
        application.setCreatedBy(username);

        List<OpenItem> involved = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int lineNumber = 1;
        for (CreditApplicationLineRequest l : request.getLines()) {
            OpenItem credit = openItemService.require(l.getCreditItemId(), company);
            OpenItem charge = openItemService.require(l.getChargeItemId(), company);
            if (credit.getSide() != AccountSide.PAYABLE || charge.getSide() != AccountSide.PAYABLE
                    || !credit.getSupplier().getId().equals(supplier.getId()) || !charge.getSupplier().getId().equals(supplier.getId())) {
                throw new IllegalArgumentException("Line " + lineNumber + ": both items must be payables of supplier " + supplier.getName());
            }
            SupplierCreditApplicationLine line = new SupplierCreditApplicationLine();
            line.setSupplierCreditApplication(application);
            line.setCreditItem(credit);
            line.setChargeItem(charge);
            line.setAmount(l.getAmount());
            line.setLineNumber(lineNumber++);
            application.getLines().add(line);
            involved.add(credit);
            involved.add(charge);
            total = total.add(l.getAmount());
        }
        application.setTotalAmount(total);

        application.setReferenceNumber(transactionReferenceService.next(company.getId(), TYPE.name(), REFERENCE_PREFIX));
        SupplierCreditApplication saved = applicationRepository.save(application);
        transactionEventService.recordSystemEvent(company, TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        openItemService.lock(involved);
        for (SupplierCreditApplicationLine line : saved.getLines()) {
            openItemService.allocate(line.getCreditItem(), line.getChargeItem(), line.getAmount(), applicationDate, TYPE, saved.getId(), username);
        }
        log.info("User '{}' posted supplier credit application (id={}) {} applying {} for supplier {}", username, saved.getId(),
                saved.getReferenceNumber(), total.toPlainString(), supplier.getId());
        return saved;
    }

    // Reverses whatever of its applications is still active (a bounced check may already have undone some).
    @Transactional
    public SupplierCreditApplication voidApplication(Long id, String username) {
        SupplierCreditApplication application = findById(id, username);
        if (application.isVoided()) {
            throw new IllegalArgumentException("Supplier credit application already voided: " + id);
        }
        Instant now = Instant.now();
        openItemService.reverseBySource(TYPE, application.getId(), AllocationReversalReason.VOIDED, application.getApplicationDate(), username);
        application.setVoided(true);
        application.setVoidedAt(now);
        application.setVoidedBy(username);
        SupplierCreditApplication saved = applicationRepository.save(application);
        transactionEventService.recordSystemEvent(saved.getCompany(), TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided supplier credit application (id={})", username, id);
        return saved;
    }
}
