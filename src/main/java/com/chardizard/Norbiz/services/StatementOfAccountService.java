package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.StatementOfAccountRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.CustomerRepository;
import com.chardizard.Norbiz.repositories.StatementOfAccountRepository;
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
import java.util.List;
import java.util.Map;

/**
 * Statement of Account (docs/ACCOUNTING.md "Statement of Account"): a customer's open items as of a date — open charges
 * aged by due date, and unapplied credits — frozen as a printable document. No ledger effect; voiding just retires it.
 */
@Service
@RequiredArgsConstructor
public class StatementOfAccountService {

    private static final Logger log = LoggerFactory.getLogger(StatementOfAccountService.class);
    private static final TransactionType TYPE = TransactionType.STATEMENT_OF_ACCOUNT;
    private static final String REFERENCE_PREFIX = "SOA";

    private final StatementOfAccountRepository statementRepository;
    private final CompanyRepository companyRepository;
    private final CustomerRepository customerRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;
    private final AccountingReportService reportService;
    private final CompanyAccess companyAccess;

    public Page<StatementOfAccount> findAllForUser(String username, Long customerId, Map<String, String> filters, Instant dateFrom, Instant dateTo,
                                                   Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        Specification<StatementOfAccount> companyScope = companyIds == null ? null
                : (root, query, cb) -> root.get("company").get("id").in(companyIds);
        return statementRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                customerId == null ? null : (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId),
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.containsIgnoreCase("customer.name", filters.get("customer")),
                SpecificationUtils.booleanEquals("voided", filters.get("voided")),
                SpecificationUtils.dateRange("statementDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        ), pageable);
    }

    public StatementOfAccount findById(Long id, String username) {
        StatementOfAccount statement = statementRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Statement of account not found: " + id));
        companyAccess.assertCompanyAccess(username, statement.getCompany().getId());
        return statement;
    }

    /** Builds (without saving) the customer's statement as of the date. */
    @Transactional(readOnly = true)
    public StatementOfAccount preview(Long companyId, Long customerId, String statementDate, String username) {
        StatementOfAccountRequest request = new StatementOfAccountRequest();
        request.setCompanyId(companyId);
        request.setCustomerId(customerId);
        request.setStatementDate(statementDate);
        return build(request, username);
    }

    @Transactional
    public StatementOfAccount create(StatementOfAccountRequest request, String username) {
        StatementOfAccount statement = build(request, username);
        statement.setReferenceNumber(transactionReferenceService.next(statement.getCompany().getId(), TYPE.name(), REFERENCE_PREFIX));
        StatementOfAccount saved = statementRepository.save(statement);
        transactionEventService.recordSystemEvent(saved.getCompany(), TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());
        log.info("User '{}' issued statement of account (id={}) {} to customer {} as of {}: balance {}", username, saved.getId(),
                saved.getReferenceNumber(), saved.getCustomer().getId(), request.getStatementDate(), saved.getTotalBalance().toPlainString());
        return saved;
    }

    @Transactional
    public StatementOfAccount voidStatement(Long id, String username) {
        StatementOfAccount statement = findById(id, username);
        if (statement.isVoided()) {
            throw new IllegalArgumentException("Statement of account already voided: " + id);
        }
        statement.setVoided(true);
        statement.setVoidedAt(Instant.now());
        statement.setVoidedBy(username);
        StatementOfAccount saved = statementRepository.save(statement);
        transactionEventService.recordSystemEvent(saved.getCompany(), TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided statement of account (id={})", username, id);
        return saved;
    }

    private StatementOfAccount build(StatementOfAccountRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));
        companyAccess.assertCompanyAccess(username, company.getId());
        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + request.getCustomerId()));
        if (!customer.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Customer does not belong to company: " + company.getId());
        }
        Instant asOf = DateRangeUtils.startOfDayUtc(request.getStatementDate());

        StatementOfAccount statement = new StatementOfAccount();
        statement.setCompany(company);
        statement.setCustomer(customer);
        statement.setStatementDate(asOf);
        statement.setSheetNumber(request.getSheetNumber());
        statement.setRemarks(request.getRemarks());
        statement.setCreatedAt(Instant.now());
        statement.setCreatedBy(username);
        BigDecimal[] buckets = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal credits = BigDecimal.ZERO;
        int lineNumber = 1;
        for (AccountingReportService.AsOf a : reportService.balancesAsOf(AccountSide.RECEIVABLE, company.getId(), customer.getId(), asOf)) {
            OpenItem item = a.item();
            boolean charge = item.getDirection() == OpenItemDirection.CHARGE;
            StatementOfAccountLine line = new StatementOfAccountLine();
            line.setStatementOfAccount(statement);
            line.setOpenItem(item);
            line.setSourceType(item.getSourceType());
            line.setDirection(item.getDirection());
            line.setReferenceNumber(item.getReferenceNumber());
            line.setDocumentDate(item.getDocumentDate());
            line.setDueDate(item.getDueDate());
            line.setAmount(item.getAmount());
            line.setPaidAmount(a.paid());
            line.setPendingAmount(a.pending());
            line.setBalance(charge ? a.balance() : a.balance().negate());
            line.setDaysOverdue(charge ? AccountingReportService.daysOverdue(item, asOf, AgingBasis.DUE_DATE) : 0);
            line.setLineNumber(lineNumber++);
            statement.getLines().add(line);
            if (charge) {
                int bucket = switch (AccountingReportService.bucket(line.getDaysOverdue())) {
                    case "CURRENT" -> 0;
                    case "DAYS_1_30" -> 1;
                    case "DAYS_31_60" -> 2;
                    case "DAYS_61_90" -> 3;
                    case "DAYS_91_120" -> 4;
                    default -> 5;
                };
                buckets[bucket] = buckets[bucket].add(a.balance());
            } else {
                credits = credits.add(a.balance());
            }
        }
        statement.setCurrentAmount(buckets[0]);
        statement.setDays1To30(buckets[1]);
        statement.setDays31To60(buckets[2]);
        statement.setDays61To90(buckets[3]);
        statement.setDays91To120(buckets[4]);
        statement.setOver120(buckets[5]);
        statement.setUnappliedCredits(credits);
        BigDecimal due = BigDecimal.ZERO;
        for (BigDecimal b : buckets) due = due.add(b);
        statement.setTotalBalance(due.subtract(credits));
        return statement;
    }
}
