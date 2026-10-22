package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.CreditApplicationLineRequest;
import com.chardizard.Norbiz.dto.CustomerCreditApplicationRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.CustomerCreditApplicationRepository;
import com.chardizard.Norbiz.repositories.CustomerRepository;
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
 * Customer Credit Application (docs/ACCOUNTING.md "Credit applications"): applies credits posted earlier — the
 * unapplied part of payment tenders (advances) and credit memos — to the customer's open charges.
 */
@Service
@RequiredArgsConstructor
public class CustomerCreditApplicationService {

    private static final Logger log = LoggerFactory.getLogger(CustomerCreditApplicationService.class);
    private static final TransactionType TYPE = TransactionType.CUSTOMER_CREDIT_APPLICATION;
    private static final String REFERENCE_PREFIX = "CCA";

    private final CustomerCreditApplicationRepository applicationRepository;
    private final CompanyRepository companyRepository;
    private final CustomerRepository customerRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;
    private final AccountingSettingsService accountingSettingsService;
    private final OpenItemService openItemService;
    private final CompanyAccess companyAccess;

    public Page<CustomerCreditApplication> findAllForUser(String username, Long customerId, Map<String, String> filters,
                                                          Instant dateFrom, Instant dateTo, Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        Specification<CustomerCreditApplication> companyScope = companyIds == null ? null
                : (root, query, cb) -> root.get("company").get("id").in(companyIds);

        return applicationRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                customerId == null ? null : (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId),
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.containsIgnoreCase("customer.name", filters.get("customer")),
                SpecificationUtils.containsIgnoreCase("remarks", filters.get("remarks")),
                SpecificationUtils.booleanEquals("voided", filters.get("voided")),
                SpecificationUtils.dateRange("applicationDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        ), pageable);
    }

    public CustomerCreditApplication findById(Long id, String username) {
        CustomerCreditApplication application = applicationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Customer credit application not found: " + id));
        companyAccess.assertCompanyAccess(username, application.getCompany().getId());
        return application;
    }

    @Transactional
    public CustomerCreditApplication create(CustomerCreditApplicationRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));
        companyAccess.assertCompanyAccess(username, company.getId());
        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + request.getCustomerId()));
        if (!customer.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Customer does not belong to company: " + company.getId());
        }
        Instant applicationDate = DateRangeUtils.startOfDayUtc(request.getApplicationDate());
        accountingSettingsService.assertOnOrAfterStart(company, applicationDate, "Application");

        Instant now = Instant.now();
        CustomerCreditApplication application = new CustomerCreditApplication();
        application.setCompany(company);
        application.setCustomer(customer);
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
            if (credit.getSide() != AccountSide.RECEIVABLE || charge.getSide() != AccountSide.RECEIVABLE
                    || !credit.getCustomer().getId().equals(customer.getId()) || !charge.getCustomer().getId().equals(customer.getId())) {
                throw new IllegalArgumentException("Line " + lineNumber + ": both items must be receivables of customer " + customer.getName());
            }
            CustomerCreditApplicationLine line = new CustomerCreditApplicationLine();
            line.setCustomerCreditApplication(application);
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
        CustomerCreditApplication saved = applicationRepository.save(application);
        transactionEventService.recordSystemEvent(company, TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        openItemService.lock(involved);
        for (CustomerCreditApplicationLine line : saved.getLines()) {
            openItemService.allocate(line.getCreditItem(), line.getChargeItem(), line.getAmount(), applicationDate, TYPE, saved.getId(), username);
        }
        log.info("User '{}' posted customer credit application (id={}) {} applying {} for customer {}", username, saved.getId(),
                saved.getReferenceNumber(), total.toPlainString(), customer.getId());
        return saved;
    }

    // Reverses whatever of its applications is still active (a bounced check may already have undone some).
    @Transactional
    public CustomerCreditApplication voidApplication(Long id, String username) {
        CustomerCreditApplication application = findById(id, username);
        if (application.isVoided()) {
            throw new IllegalArgumentException("Customer credit application already voided: " + id);
        }
        Instant now = Instant.now();
        openItemService.reverseBySource(TYPE, application.getId(), AllocationReversalReason.VOIDED, application.getApplicationDate(), username);
        application.setVoided(true);
        application.setVoidedAt(now);
        application.setVoidedBy(username);
        CustomerCreditApplication saved = applicationRepository.save(application);
        transactionEventService.recordSystemEvent(saved.getCompany(), TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided customer credit application (id={})", username, id);
        return saved;
    }
}
