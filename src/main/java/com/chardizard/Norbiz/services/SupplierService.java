package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.SupplierRequest;
import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.Supplier;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.SupplierRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SupplierService {

    private static final Logger log = LoggerFactory.getLogger(SupplierService.class);

    private final SupplierRepository supplierRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;

    public Page<Supplier> findAllForUser(String username, Map<String, String> filters, Instant updatedAtFrom, Instant updatedAtTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<Supplier> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<Supplier> spec = SpecificationUtils.allOf(
                companyScope,
                SpecificationUtils.containsIgnoreCase("name", filters.get("name")),
                SpecificationUtils.containsIgnoreCase("code", filters.get("code")),
                SpecificationUtils.containsIgnoreCase("company.name", filters.get("company")),
                SpecificationUtils.containsIgnoreCase("createdBy", filters.get("createdBy")),
                SpecificationUtils.containsIgnoreCase("active", filters.get("active")),
                SpecificationUtils.dateRange("updatedAt", updatedAtFrom, updatedAtTo)
        );

        return supplierRepository.findAll(spec, pageable);
    }

    public Supplier findById(Long id, String username) {
        Supplier supplier = supplierRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Supplier not found: " + id));
        assertCompanyAccess(username, supplier.getCompany().getId());
        return supplier;
    }

    @Transactional
    public Supplier create(SupplierRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        if (StringUtils.hasText(request.getCode())
                && supplierRepository.existsByCodeAndCompanyId(request.getCode(), company.getId())) {
            throw new IllegalArgumentException("Supplier code already exists for this company: " + request.getCode());
        }

        Supplier supplier = new Supplier();
        supplier.setCompany(company);
        supplier.setCode(request.getCode());
        supplier.setName(request.getName());
        supplier.setEmail(request.getEmail());
        supplier.setPhone(request.getPhone());
        supplier.setActive(request.isActive());

        Supplier saved = supplierRepository.save(supplier);
        log.info("User '{}' created supplier '{}' (id={}) for company {}", username, saved.getName(), saved.getId(), company.getId());
        return saved;
    }

    @Transactional
    public Supplier update(Long id, SupplierRequest request, String username) {
        Supplier supplier = findById(id, username);

        if (StringUtils.hasText(request.getCode())
                && !request.getCode().equals(supplier.getCode())
                && supplierRepository.existsByCodeAndCompanyId(request.getCode(), supplier.getCompany().getId())) {
            throw new IllegalArgumentException("Supplier code already exists for this company: " + request.getCode());
        }

        supplier.setCode(request.getCode());
        supplier.setName(request.getName());
        supplier.setEmail(request.getEmail());
        supplier.setPhone(request.getPhone());
        supplier.setActive(request.isActive());

        Supplier saved = supplierRepository.save(supplier);
        log.info("User '{}' updated supplier '{}' (id={})", username, saved.getName(), saved.getId());
        return saved;
    }

    @Transactional
    public void delete(Long id, String username) {
        Supplier supplier = findById(id, username);
        supplierRepository.delete(supplier);
        log.info("User '{}' deleted supplier '{}' (id={})", username, supplier.getName(), id);
    }

    private void assertCompanyAccess(String username, Long companyId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        if (isSuperAdmin) return;

        boolean hasAccess = user.getCompanies().stream()
                .anyMatch(c -> c.getId().equals(companyId));

        if (!hasAccess) {
            log.warn("User '{}' denied access to company {}", username, companyId);
            throw new SecurityException("Access denied to company: " + companyId);
        }
    }
}