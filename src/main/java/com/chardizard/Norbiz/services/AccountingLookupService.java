package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.cache.CacheRegion;
import com.chardizard.Norbiz.cache.CacheScope;
import com.chardizard.Norbiz.cache.QueryCache;
import com.chardizard.Norbiz.dto.AccountingResponses;
import com.chardizard.Norbiz.dto.BankAccountLookupResponse;
import com.chardizard.Norbiz.dto.LookupResponse;
import com.chardizard.Norbiz.dto.OpenItemResponse;
import com.chardizard.Norbiz.dto.TransactionLookupResponse;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.BankAccountRepository;
import com.chardizard.Norbiz.repositories.BankRepository;
import com.chardizard.Norbiz.repositories.DeliveryReceiptRepository;
import com.chardizard.Norbiz.security.CompanyAccess;
import com.chardizard.Norbiz.util.SpecificationUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.chardizard.Norbiz.cache.QueryCache.params;

/**
 * Dropdowns for the AR/AP forms (see LookupService for the rules every lookup follows): one company per call, access
 * checked before the cache. Open items are not cached — their balances change with every posting (like /lookups/stock).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountingLookupService {

    private final BankRepository bankRepository;
    private final BankAccountRepository bankAccountRepository;
    private final DeliveryReceiptRepository deliveryReceiptRepository;
    private final OpenItemQueryService openItemQueryService;
    private final QueryCache queryCache;
    private final CompanyAccess companyAccess;

    public Page<LookupResponse> banks(String username, Long companyId, String q, boolean activeOnly, Pageable pageable) {
        Long company = requireCompany(companyId, username);
        Pageable sorted = withDefaultSort(pageable, Sort.by("name"));
        return queryCache.page(CacheRegion.LOOKUP_BANK, CacheScope.company(company), params("q", q, "activeOnly", activeOnly), sorted,
                LookupResponse.class, () -> bankRepository.findAll(SpecificationUtils.allOf(
                        (root, query, cb) -> cb.equal(root.get("company").get("id"), company),
                        SpecificationUtils.anyContainsIgnoreCase(q, "code", "name"),
                        activeOnly ? SpecificationUtils.booleanEquals("active", true) : null), sorted)
                        .map(b -> new LookupResponse(b.getId(), b.getCompany().getId(), b.getCode(), b.getName(), b.isActive())));
    }

    public LookupResponse bank(Long id, String username) {
        Bank b = bankRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Bank not found: " + id));
        companyAccess.assertCompanyAccess(username, b.getCompany().getId());
        return new LookupResponse(b.getId(), b.getCompany().getId(), b.getCode(), b.getName(), b.isActive());
    }

    public Page<BankAccountLookupResponse> bankAccounts(String username, Long companyId, String q, boolean activeOnly, Pageable pageable) {
        Long company = requireCompany(companyId, username);
        Pageable sorted = withDefaultSort(pageable, Sort.by("name"));
        return queryCache.page(CacheRegion.LOOKUP_BANK_ACCOUNT, CacheScope.company(company), params("q", q, "activeOnly", activeOnly), sorted,
                BankAccountLookupResponse.class, () -> bankAccountRepository.findAll(SpecificationUtils.allOf(
                        (root, query, cb) -> cb.equal(root.get("company").get("id"), company),
                        SpecificationUtils.anyContainsIgnoreCase(q, "accountNumber", "name", "bank.name"),
                        activeOnly ? SpecificationUtils.booleanEquals("active", true) : null), sorted)
                        .map(AccountingLookupService::toLookup));
    }

    public BankAccountLookupResponse bankAccount(Long id, String username) {
        BankAccount a = bankAccountRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Bank account not found: " + id));
        companyAccess.assertCompanyAccess(username, a.getCompany().getId());
        return toLookup(a);
    }

    /** One party's OPEN items, for picking what a payment/memo/application/refund applies to. */
    public Page<OpenItemResponse> openItems(String username, Long companyId, AccountSide side, Long customerId, Long supplierId,
                                            OpenItemDirection direction, String q, Pageable pageable) {
        if ((side == AccountSide.RECEIVABLE ? customerId : supplierId) == null) {
            throw new IllegalArgumentException(side == AccountSide.RECEIVABLE ? "customerId is required for receivables" : "supplierId is required for payables");
        }
        Pageable sorted = withDefaultSort(pageable, Sort.by("dueDate", "documentDate", "id"));
        return openItemQueryService.lookup(username, companyId, side, side == AccountSide.RECEIVABLE ? customerId : null,
                side == AccountSide.PAYABLE ? supplierId : null, direction, q, sorted).map(AccountingResponses::openItem);
    }

    /** Plain-customer Delivery Receipts a Sales Invoice can still bill: not voided, not billed. */
    public Page<TransactionLookupResponse> billableDeliveryReceipts(String username, Long companyId, String q, Long customerId, Pageable pageable) {
        Long company = requireCompany(companyId, username);
        Pageable sorted = withDefaultSort(pageable, Sort.by(Sort.Direction.DESC, "deliveryDate"));
        return queryCache.page(CacheRegion.LOOKUP_BILLABLE_DELIVERY_RECEIPT, CacheScope.company(company), params("q", q, "customerId", customerId),
                sorted, TransactionLookupResponse.class, () -> deliveryReceiptRepository.findAll(SpecificationUtils.allOf(
                        (root, query, cb) -> cb.equal(root.get("company").get("id"), company),
                        (root, query, cb) -> cb.equal(root.get("customer").get("type"), CustomerType.CUSTOMER),
                        SpecificationUtils.booleanEquals("voided", false),
                        SpecificationUtils.booleanEquals("billed", false),
                        customerId == null ? null : (root, query, cb) -> cb.equal(root.get("customer").get("id"), customerId),
                        SpecificationUtils.anyContainsIgnoreCase(q, "referenceNumber", "sheetNumber")), sorted)
                        .map(AccountingLookupService::toLookup));
    }

    private static BankAccountLookupResponse toLookup(BankAccount a) {
        return new BankAccountLookupResponse(a.getId(), a.getCompany().getId(), a.getAccountNumber(), a.getName(), a.isActive(),
                a.getBank().getId(), a.getBank().getName());
    }

    private static TransactionLookupResponse toLookup(DeliveryReceipt dr) {
        List<TransactionLookupResponse.Line> lines = dr.getLines().stream()
                .map(l -> new TransactionLookupResponse.Line(l.getId(), l.getLineNumber(), l.getItem().getId(), l.getItem().getItemCode(),
                        l.getItem().getName(), l.getQuantity(), l.getQuantityLoaded(), null, l.getUnitPrice()))
                .toList();
        return new TransactionLookupResponse(dr.getId(), dr.getCompany().getId(), dr.getReferenceNumber(), dr.getDeliveryDate(), null, null,
                dr.getCustomer().getId(), dr.getCustomer().getName(), dr.getWarehouse().getId(), dr.getWarehouse().getName(), null, null, null,
                dr.isVoided(), dr.isBilled(), lines);
    }

    private Long requireCompany(Long companyId, String username) {
        if (companyId == null) {
            throw new IllegalArgumentException("companyId is required (query parameter or X-Company-Id header)");
        }
        companyAccess.assertCompanyAccess(username, companyId);
        return companyId;
    }

    private static Pageable withDefaultSort(Pageable pageable, Sort sort) {
        if (pageable.getSort().isSorted()) return pageable;
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
