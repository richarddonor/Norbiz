package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.ItemCategoryRequest;
import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.ItemCategory;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.ItemCategoryRepository;
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
public class ItemCategoryService {

    private static final Logger log = LoggerFactory.getLogger(ItemCategoryService.class);

    private final ItemCategoryRepository itemCategoryRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;

    public Page<ItemCategory> findAllForUser(String username, Map<String, String> filters, Instant updatedAtFrom, Instant updatedAtTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<ItemCategory> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<ItemCategory> spec = SpecificationUtils.allOf(
                companyScope,
                SpecificationUtils.containsIgnoreCase("name", filters.get("name")),
                SpecificationUtils.containsIgnoreCase("company.name", filters.get("company")),
                SpecificationUtils.containsIgnoreCase("createdBy", filters.get("createdBy")),
                SpecificationUtils.dateRange("updatedAt", updatedAtFrom, updatedAtTo)
        );

        return itemCategoryRepository.findAll(spec, pageable);
    }

    public ItemCategory findById(Long id, String username) {
        ItemCategory category = itemCategoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Item category not found: " + id));
        assertCompanyAccess(username, category.getCompany().getId());
        return category;
    }

    @Transactional
    public ItemCategory create(ItemCategoryRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        if (itemCategoryRepository.existsByNameAndCompanyId(request.getName(), company.getId())) {
            throw new IllegalArgumentException("Item category name already exists for this company: " + request.getName());
        }

        ItemCategory category = new ItemCategory();
        category.setCompany(company);
        category.setName(request.getName());
        return itemCategoryRepository.save(category);
    }

    @Transactional
    public ItemCategory update(Long id, ItemCategoryRequest request, String username) {
        ItemCategory category = findById(id, username);

        if (!category.getName().equals(request.getName())
                && itemCategoryRepository.existsByNameAndCompanyId(request.getName(), category.getCompany().getId())) {
            throw new IllegalArgumentException("Item category name already exists for this company: " + request.getName());
        }

        category.setName(request.getName());
        return itemCategoryRepository.save(category);
    }

    @Transactional
    public void delete(Long id, String username) {
        ItemCategory category = findById(id, username);
        itemCategoryRepository.delete(category);
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