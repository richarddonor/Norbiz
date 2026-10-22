package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.BankRequest;
import com.chardizard.Norbiz.models.Bank;
import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.repositories.BankRepository;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.security.CompanyAccess;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BankService {

    private static final Logger log = LoggerFactory.getLogger(BankService.class);

    private final BankRepository bankRepository;
    private final CompanyRepository companyRepository;
    private final CompanyAccess companyAccess;

    public Page<Bank> findAllForUser(String username, Map<String, String> filters, Instant updatedAtFrom, Instant updatedAtTo, Pageable pageable) {
        List<Long> companyIds = companyAccess.visibleCompanyIds(username);
        if (companyIds != null && companyIds.isEmpty()) return Page.empty(pageable);
        Specification<Bank> companyScope = companyIds == null ? null : (root, query, cb) -> root.get("company").get("id").in(companyIds);

        return bankRepository.findAll(SpecificationUtils.allOf(
                companyScope,
                SpecificationUtils.containsIgnoreCase("code", filters.get("code")),
                SpecificationUtils.containsIgnoreCase("name", filters.get("name")),
                SpecificationUtils.containsIgnoreCase("company.name", filters.get("company")),
                SpecificationUtils.containsIgnoreCase("createdBy", filters.get("createdBy")),
                SpecificationUtils.booleanEquals("active", filters.get("active")),
                SpecificationUtils.dateRange("updatedAt", updatedAtFrom, updatedAtTo)
        ), pageable);
    }

    public Bank findById(Long id, String username) {
        Bank bank = bankRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Bank not found: " + id));
        companyAccess.assertCompanyAccess(username, bank.getCompany().getId());
        return bank;
    }

    @Transactional
    public Bank create(BankRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));
        companyAccess.assertCompanyAccess(username, company.getId());
        if (bankRepository.existsByCodeAndCompanyId(request.getCode(), company.getId())) {
            throw new IllegalArgumentException("Bank code already exists for this company: " + request.getCode());
        }
        Bank bank = new Bank();
        bank.setCompany(company);
        apply(bank, request);
        Bank saved = bankRepository.save(bank);
        log.info("User '{}' created bank '{}' (id={}) for company {}", username, saved.getName(), saved.getId(), company.getId());
        return saved;
    }

    @Transactional
    public Bank update(Long id, BankRequest request, String username) {
        Bank bank = findById(id, username);
        if (!request.getCode().equals(bank.getCode())
                && bankRepository.existsByCodeAndCompanyId(request.getCode(), bank.getCompany().getId())) {
            throw new IllegalArgumentException("Bank code already exists for this company: " + request.getCode());
        }
        apply(bank, request);
        Bank saved = bankRepository.save(bank);
        log.info("User '{}' updated bank '{}' (id={})", username, saved.getName(), saved.getId());
        return saved;
    }

    @Transactional
    public void delete(Long id, String username) {
        Bank bank = findById(id, username);
        ForeignKeyViolations.deleteOrThrow(bankRepository, bank, "Bank", id);
        log.info("User '{}' deleted bank '{}' (id={})", username, bank.getName(), id);
    }

    private static void apply(Bank bank, BankRequest request) {
        bank.setCode(request.getCode());
        bank.setName(request.getName());
        bank.setActive(request.isActive());
    }
}
