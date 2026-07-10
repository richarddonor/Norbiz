package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.BrandRequest;
import com.chardizard.Norbiz.models.Brand;
import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.BrandRepository;
import com.chardizard.Norbiz.repositories.CompanyRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BrandService {

    private static final Logger log = LoggerFactory.getLogger(BrandService.class);

    private final BrandRepository brandRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;

    public Page<Brand> findAllForUser(String username, Map<String, String> filters, Instant updatedAtFrom, Instant updatedAtTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<Brand> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<Brand> spec = SpecificationUtils.allOf(
                companyScope,
                SpecificationUtils.containsIgnoreCase("name", filters.get("name")),
                SpecificationUtils.containsIgnoreCase("createdBy", filters.get("createdBy")),
                SpecificationUtils.dateRange("updatedAt", updatedAtFrom, updatedAtTo)
        );

        return brandRepository.findAll(spec, pageable);
    }

    public Brand findById(Long id, String username) {
        Brand brand = brandRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Brand not found: " + id));
        assertCompanyAccess(username, brand.getCompany().getId());
        return brand;
    }

    @Transactional
    public Brand create(BrandRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        if (brandRepository.existsByNameAndCompanyId(request.getName(), company.getId())) {
            throw new IllegalArgumentException("Brand name already exists for this company: " + request.getName());
        }

        Brand brand = new Brand();
        brand.setCompany(company);
        brand.setName(request.getName());
        return brandRepository.save(brand);
    }

    @Transactional
    public Brand update(Long id, BrandRequest request, String username) {
        Brand brand = findById(id, username);

        if (!brand.getName().equals(request.getName())
                && brandRepository.existsByNameAndCompanyId(request.getName(), brand.getCompany().getId())) {
            throw new IllegalArgumentException("Brand name already exists for this company: " + request.getName());
        }

        brand.setName(request.getName());
        return brandRepository.save(brand);
    }

    @Transactional
    public void delete(Long id, String username) {
        Brand brand = findById(id, username);
        brandRepository.delete(brand);
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