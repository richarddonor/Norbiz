package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.AmountLineRequest;
import com.chardizard.Norbiz.dto.ApplicationRequest;
import com.chardizard.Norbiz.dto.CustomerMemoRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.CustomerMemoRepository;
import com.chardizard.Norbiz.repositories.CustomerRepository;
import com.chardizard.Norbiz.security.CompanyAccess;
import com.chardizard.Norbiz.util.AccountingAmounts;
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
 * Customer debit/credit memos (docs/ACCOUNTING.md "Memos"). A DEBIT memo opens a RECEIVABLE CHARGE (billing an
 * outlet's sales, a price adjustment, a carried-over balance...); a CREDIT memo opens a RECEIVABLE CREDIT (sales
 * return, rebate...) that can be applied to open charges right away or later by a Customer Credit Application.
 */
@Service
@RequiredArgsConstructor
public class CustomerMemoService {

    private static final Logger log = LoggerFactory.getLogger(CustomerMemoService.class);
    private static final TransactionType TYPE = TransactionType.CUSTOMER_MEMO;

    private final CustomerMemoRepository memoRepository;
    private final CompanyRepository companyRepository;
    private final CustomerRepository customerRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;
    private final AccountingSettingsService accountingSettingsService;
    private final OpenItemService openItemService;
    private final CompanyAccess companyAccess;

    public Page<CustomerMemo> findAllForUser(String username, Long customerId, MemoType memoType, CustomerMemoReason reason,
                                             Map<String, String> filters, Instant dateFrom, Instant dateTo, Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        Specification<CustomerMemo> companyScope = companyIds == null ? null : (root, query, cb) -> root.get("company").get("id").in(companyIds);

        return memoRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                customerId == null ? null : (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId),
                memoType == null ? null : (root, query, cb) -> cb.equal(root.get("memoType"), memoType),
                reason == null ? null : (root, query, cb) -> cb.equal(root.get("reason"), reason),
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.containsIgnoreCase("customer.name", filters.get("customer")),
                SpecificationUtils.containsIgnoreCase("remarks", filters.get("remarks")),
                SpecificationUtils.booleanEquals("voided", filters.get("voided")),
                SpecificationUtils.dateRange("memoDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        ), pageable);
    }

    public CustomerMemo findById(Long id, String username) {
        CustomerMemo memo = memoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Customer memo not found: " + id));
        companyAccess.assertCompanyAccess(username, memo.getCompany().getId());
        return memo;
    }

    @Transactional
    public CustomerMemo create(CustomerMemoRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));
        companyAccess.assertCompanyAccess(username, company.getId());

        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + request.getCustomerId()));
        if (!customer.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Customer does not belong to company: " + company.getId());
        }
        if (!customer.isActive()) {
            throw new IllegalArgumentException("Customer is inactive: " + customer.getName());
        }

        Instant memoDate = DateRangeUtils.startOfDayUtc(request.getMemoDate());
        MemoDates.assertPlacement(accountingSettingsService.requireStartDate(company), memoDate,
                request.getReason() == CustomerMemoReason.OPENING_BALANCE);

        OpenItemDirection direction = request.getMemoType() == MemoType.DEBIT ? OpenItemDirection.CHARGE : OpenItemDirection.CREDIT;
        List<ApplicationRequest> applications = request.getApplications() != null ? request.getApplications() : List.of();
        if (direction == OpenItemDirection.CHARGE && !applications.isEmpty()) {
            throw new IllegalArgumentException("Only a CREDIT memo can be applied to open charges");
        }

        Instant now = Instant.now();
        CustomerMemo memo = new CustomerMemo();
        memo.setCompany(company);
        memo.setCustomer(customer);
        memo.setMemoType(request.getMemoType());
        memo.setReason(request.getReason());
        memo.setMemoDate(memoDate);
        memo.setOriginalReference(request.getOriginalReference());
        memo.setSheetNumber(request.getSheetNumber());
        memo.setRemarks(request.getRemarks());
        memo.setCreatedAt(now);
        memo.setCreatedBy(username);
        if (direction == OpenItemDirection.CHARGE) {
            memo.setTermDays(request.getTermDays() != null ? request.getTermDays() : customer.getTermDays());
            memo.setDueDate(MemoDates.dueDate(memoDate, memo.getTermDays(), request.getDueDate(),
                    request.getReason() == CustomerMemoReason.OPENING_BALANCE));
        }
        BigDecimal amount = BigDecimal.ZERO;
        int lineNumber = 1;
        for (AmountLineRequest lineRequest : request.getLines()) {
            CustomerMemoLine line = new CustomerMemoLine();
            line.setCustomerMemo(memo);
            line.setDescription(lineRequest.getDescription());
            line.setAmount(lineRequest.getAmount());
            line.setLineNumber(lineNumber++);
            memo.getLines().add(line);
            amount = amount.add(lineRequest.getAmount());
        }
        memo.setAmount(amount);
        List<OpenItem> charges = new ArrayList<>();
        for (ApplicationRequest application : applications) {
            charges.add(openItemService.require(application.getOpenItemId(), company));
        }

        String prefix = direction == OpenItemDirection.CHARGE ? "CDM" : "CCM";
        memo.setReferenceNumber(transactionReferenceService.next(company.getId(), TYPE.name() + "_" + request.getMemoType().name(), prefix));
        CustomerMemo saved = memoRepository.save(memo);
        transactionEventService.recordSystemEvent(company, TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        OpenItem item = openItemService.open(company, AccountSide.RECEIVABLE, customer, null, direction,
                direction == OpenItemDirection.CHARGE ? OpenItemSourceType.CUSTOMER_DEBIT_MEMO : OpenItemSourceType.CUSTOMER_CREDIT_MEMO,
                saved.getId(), saved.getReferenceNumber(), memoDate, saved.getDueDate(), amount, true, username);
        saved.setOpenItem(item);
        List<OpenItem> toLock = new ArrayList<>(charges);
        toLock.add(item);
        openItemService.lock(toLock);
        for (int i = 0; i < applications.size(); i++) {
            openItemService.allocate(item, charges.get(i), applications.get(i).getAmount(), memoDate, TYPE, saved.getId(), username);
        }

        log.info("User '{}' posted customer {} memo (id={}) {} for {} to customer {} ({} application(s))", username, request.getMemoType(),
                saved.getId(), saved.getReferenceNumber(), AccountingAmounts.money(amount).toPlainString(), customer.getId(), applications.size());
        return saved;
    }

    // Reverses the applications it made itself; blocked while other documents have applied to/from it.
    @Transactional
    public CustomerMemo voidMemo(Long id, String username) {
        CustomerMemo memo = findById(id, username);
        if (memo.isVoided()) {
            throw new IllegalArgumentException("Customer memo already voided: " + id);
        }
        if (openItemService.appliedByOthers(memo.getOpenItem(), TYPE, memo.getId())) {
            throw new IllegalArgumentException("Cannot void " + memo.getReferenceNumber()
                    + ": other payments/applications are applied to it — void those first");
        }
        Instant now = Instant.now();
        openItemService.reverseBySource(TYPE, memo.getId(), AllocationReversalReason.VOIDED, memo.getMemoDate(), username);
        openItemService.voidItem(memo.getOpenItem(), username);
        memo.setVoided(true);
        memo.setVoidedAt(now);
        memo.setVoidedBy(username);
        CustomerMemo saved = memoRepository.save(memo);
        transactionEventService.recordSystemEvent(saved.getCompany(), TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided customer memo (id={})", username, id);
        return saved;
    }
}
