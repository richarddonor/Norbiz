package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.BankAccountRequest;
import com.chardizard.Norbiz.models.Bank;
import com.chardizard.Norbiz.models.BankAccount;
import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.repositories.BankAccountRepository;
import com.chardizard.Norbiz.repositories.BankRepository;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.security.CompanyAccess;
import com.chardizard.Norbiz.util.DateRangeUtils;
import com.chardizard.Norbiz.util.ForeignKeyViolations;
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

@Service
@RequiredArgsConstructor
public class BankAccountService {

    private static final Logger log = LoggerFactory.getLogger(BankAccountService.class);

    private final BankAccountRepository bankAccountRepository;
    private final BankRepository bankRepository;
    private final CompanyRepository companyRepository;
    private final CompanyAccess companyAccess;

    public Page<BankAccount> findAllForUser(String username, Long bankId, Map<String, String> filters, Instant updatedAtFrom,
                                            Instant updatedAtTo, Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        Specification<BankAccount> companyScope = companyIds == null ? null : (root, query, cb) -> root.get("company").get("id").in(companyIds);
        Specification<BankAccount> bankScope = bankId == null ? null : (root, query, cb) -> cb.equal(root.get("bank").get("id"), bankId);

        return bankAccountRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                bankScope,
                SpecificationUtils.containsIgnoreCase("name", filters.get("name")),
                SpecificationUtils.containsIgnoreCase("accountNumber", filters.get("accountNumber")),
                SpecificationUtils.containsIgnoreCase("bank.name", filters.get("bank")),
                SpecificationUtils.containsIgnoreCase("company.name", filters.get("company")),
                SpecificationUtils.containsIgnoreCase("createdBy", filters.get("createdBy")),
                SpecificationUtils.booleanEquals("active", filters.get("active")),
                SpecificationUtils.dateRange("updatedAt", updatedAtFrom, updatedAtTo)
        ), pageable);
    }

    public BankAccount findById(Long id, String username) {
        BankAccount account = bankAccountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Bank account not found: " + id));
        companyAccess.assertCompanyAccess(username, account.getCompany().getId());
        return account;
    }

    @Transactional
    public BankAccount create(BankAccountRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));
        companyAccess.assertCompanyAccess(username, company.getId());
        if (bankAccountRepository.existsByAccountNumberAndCompanyId(request.getAccountNumber(), company.getId())) {
            throw new IllegalArgumentException("Bank account number already exists for this company: " + request.getAccountNumber());
        }
        BankAccount account = new BankAccount();
        account.setCompany(company);
        apply(account, request, company, true);
        BankAccount saved = bankAccountRepository.save(account);
        log.info("User '{}' created bank account '{}' (id={}) for company {}", username, saved.getName(), saved.getId(), company.getId());
        return saved;
    }

    @Transactional
    public BankAccount update(Long id, BankAccountRequest request, String username) {
        BankAccount account = findById(id, username);
        if (!request.getAccountNumber().equals(account.getAccountNumber())
                && bankAccountRepository.existsByAccountNumberAndCompanyId(request.getAccountNumber(), account.getCompany().getId())) {
            throw new IllegalArgumentException("Bank account number already exists for this company: " + request.getAccountNumber());
        }
        apply(account, request, account.getCompany(), !request.getBankId().equals(account.getBank().getId()));
        BankAccount saved = bankAccountRepository.save(account);
        log.info("User '{}' updated bank account '{}' (id={})", username, saved.getName(), saved.getId());
        return saved;
    }

    @Transactional
    public void delete(Long id, String username) {
        BankAccount account = findById(id, username);
        ForeignKeyViolations.deleteOrThrow(bankAccountRepository, account, "Bank Account", id);
        log.info("User '{}' deleted bank account '{}' (id={})", username, account.getName(), id);
    }

    // A bank picked anew must be active; keeping an account's current (since deactivated) bank is fine.
    private void apply(BankAccount account, BankAccountRequest request, Company company, boolean bankChanged) {
        Bank bank = bankRepository.findById(request.getBankId())
                .orElseThrow(() -> new IllegalArgumentException("Bank not found: " + request.getBankId()));
        if (!bank.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Bank does not belong to company: " + company.getId());
        }
        if (bankChanged && !bank.isActive()) {
            throw new IllegalArgumentException("Bank is inactive: " + bank.getName());
        }
        account.setBank(bank);
        account.setName(request.getName());
        account.setAccountNumber(request.getAccountNumber());
        account.setOpeningBalance(request.getOpeningBalance() != null ? request.getOpeningBalance() : BigDecimal.ZERO);
        account.setOpeningBalanceDate(DateRangeUtils.startOfDayUtc(request.getOpeningBalanceDate()));
        account.setActive(request.isActive());
    }
}
