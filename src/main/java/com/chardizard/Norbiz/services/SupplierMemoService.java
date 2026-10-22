package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.AmountLineRequest;
import com.chardizard.Norbiz.dto.ApplicationRequest;
import com.chardizard.Norbiz.dto.SupplierMemoRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.SupplierMemoRepository;
import com.chardizard.Norbiz.repositories.SupplierRepository;
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
 * Supplier debit/credit memos (docs/ACCOUNTING.md "Memos"). A DEBIT memo (legacy SDM — we debit the supplier, e.g. a
 * purchase return or rebate) opens a PAYABLE CREDIT that lowers what we owe and can be applied to open payables right
 * away or later; a CREDIT memo (legacy SCM — the supplier's account is credited, e.g. an extra charge or a balance
 * carried over) opens a PAYABLE CHARGE.
 */
@Service
@RequiredArgsConstructor
public class SupplierMemoService {

    private static final Logger log = LoggerFactory.getLogger(SupplierMemoService.class);
    private static final TransactionType TYPE = TransactionType.SUPPLIER_MEMO;

    private final SupplierMemoRepository memoRepository;
    private final CompanyRepository companyRepository;
    private final SupplierRepository supplierRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;
    private final AccountingSettingsService accountingSettingsService;
    private final OpenItemService openItemService;
    private final CompanyAccess companyAccess;

    public Page<SupplierMemo> findAllForUser(String username, Long supplierId, MemoType memoType, SupplierMemoReason reason,
                                             Map<String, String> filters, Instant dateFrom, Instant dateTo, Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        Specification<SupplierMemo> companyScope = companyIds == null ? null : (root, query, cb) -> root.get("company").get("id").in(companyIds);

        return memoRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                supplierId == null ? null : (root, query, cb) -> cb.equal(root.get("supplier").get("id"), supplierId),
                memoType == null ? null : (root, query, cb) -> cb.equal(root.get("memoType"), memoType),
                reason == null ? null : (root, query, cb) -> cb.equal(root.get("reason"), reason),
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.containsIgnoreCase("supplier.name", filters.get("supplier")),
                SpecificationUtils.containsIgnoreCase("remarks", filters.get("remarks")),
                SpecificationUtils.booleanEquals("voided", filters.get("voided")),
                SpecificationUtils.dateRange("memoDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        ), pageable);
    }

    public SupplierMemo findById(Long id, String username) {
        SupplierMemo memo = memoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Supplier memo not found: " + id));
        companyAccess.assertCompanyAccess(username, memo.getCompany().getId());
        return memo;
    }

    @Transactional
    public SupplierMemo create(SupplierMemoRequest request, String username) {
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

        Instant memoDate = DateRangeUtils.startOfDayUtc(request.getMemoDate());
        MemoDates.assertPlacement(accountingSettingsService.requireStartDate(company), memoDate,
                request.getReason() == SupplierMemoReason.OPENING_BALANCE);

        // Supplier DEBIT memo (we debit the supplier, e.g. a purchase return) lowers what we owe; CREDIT memo raises it.
        OpenItemDirection direction = request.getMemoType() == MemoType.DEBIT ? OpenItemDirection.CREDIT : OpenItemDirection.CHARGE;
        List<ApplicationRequest> applications = request.getApplications() != null ? request.getApplications() : List.of();
        if (direction == OpenItemDirection.CHARGE && !applications.isEmpty()) {
            throw new IllegalArgumentException("Only a DEBIT memo can be applied to open payables");
        }

        Instant now = Instant.now();
        SupplierMemo memo = new SupplierMemo();
        memo.setCompany(company);
        memo.setSupplier(supplier);
        memo.setMemoType(request.getMemoType());
        memo.setReason(request.getReason());
        memo.setMemoDate(memoDate);
        memo.setOriginalReference(request.getOriginalReference());
        memo.setSheetNumber(request.getSheetNumber());
        memo.setRemarks(request.getRemarks());
        memo.setCreatedAt(now);
        memo.setCreatedBy(username);
        if (direction == OpenItemDirection.CHARGE) {
            memo.setTermDays(request.getTermDays() != null ? request.getTermDays() : supplier.getTermDays());
            memo.setDueDate(MemoDates.dueDate(memoDate, memo.getTermDays(), request.getDueDate(),
                    request.getReason() == SupplierMemoReason.OPENING_BALANCE));
        }
        BigDecimal amount = BigDecimal.ZERO;
        int lineNumber = 1;
        for (AmountLineRequest lineRequest : request.getLines()) {
            SupplierMemoLine line = new SupplierMemoLine();
            line.setSupplierMemo(memo);
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

        String prefix = request.getMemoType() == MemoType.DEBIT ? "SDM" : "SCM";
        memo.setReferenceNumber(transactionReferenceService.next(company.getId(), TYPE.name() + "_" + request.getMemoType().name(), prefix));
        SupplierMemo saved = memoRepository.save(memo);
        transactionEventService.recordSystemEvent(company, TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        OpenItem item = openItemService.open(company, AccountSide.PAYABLE, null, supplier, direction,
                direction == OpenItemDirection.CHARGE ? OpenItemSourceType.SUPPLIER_CREDIT_MEMO : OpenItemSourceType.SUPPLIER_DEBIT_MEMO,
                saved.getId(), saved.getReferenceNumber(), memoDate, saved.getDueDate(), amount, true, username);
        saved.setOpenItem(item);
        List<OpenItem> toLock = new ArrayList<>(charges);
        toLock.add(item);
        openItemService.lock(toLock);
        for (int i = 0; i < applications.size(); i++) {
            openItemService.allocate(item, charges.get(i), applications.get(i).getAmount(), memoDate, TYPE, saved.getId(), username);
        }

        log.info("User '{}' posted supplier {} memo (id={}) {} for {} to supplier {} ({} application(s))", username, request.getMemoType(),
                saved.getId(), saved.getReferenceNumber(), AccountingAmounts.money(amount).toPlainString(), supplier.getId(), applications.size());
        return saved;
    }

    // Reverses the applications it made itself; blocked while other documents have applied to/from it.
    @Transactional
    public SupplierMemo voidMemo(Long id, String username) {
        SupplierMemo memo = findById(id, username);
        if (memo.isVoided()) {
            throw new IllegalArgumentException("Supplier memo already voided: " + id);
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
        SupplierMemo saved = memoRepository.save(memo);
        transactionEventService.recordSystemEvent(saved.getCompany(), TYPE, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided supplier memo (id={})", username, id);
        return saved;
    }
}
