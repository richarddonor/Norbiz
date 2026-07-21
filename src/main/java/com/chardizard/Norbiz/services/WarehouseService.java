package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.WarehouseRequest;
import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.models.Warehouse;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import com.chardizard.Norbiz.repositories.WarehouseRepository;
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
public class WarehouseService {

    private static final Logger log = LoggerFactory.getLogger(WarehouseService.class);

    private final WarehouseRepository warehouseRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;

    public Page<Warehouse> findAllForUser(String username, Map<String, String> filters, Instant updatedAtFrom, Instant updatedAtTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<Warehouse> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<Warehouse> spec = SpecificationUtils.allOf(
                companyScope,
                SpecificationUtils.containsIgnoreCase("name", filters.get("name")),
                SpecificationUtils.containsIgnoreCase("code", filters.get("code")),
                SpecificationUtils.containsIgnoreCase("company.name", filters.get("company")),
                SpecificationUtils.containsIgnoreCase("createdBy", filters.get("createdBy")),
                SpecificationUtils.booleanEquals("active", filters.get("active")),
                SpecificationUtils.dateRange("updatedAt", updatedAtFrom, updatedAtTo)
        );

        return warehouseRepository.findAll(spec, pageable);
    }

    public Warehouse findById(Long id, String username) {
        Warehouse warehouse = warehouseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Warehouse not found: " + id));
        assertCompanyAccess(username, warehouse.getCompany().getId());
        return warehouse;
    }

    @Transactional
    public Warehouse create(WarehouseRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        if (StringUtils.hasText(request.getCode())
                && warehouseRepository.existsByCodeAndCompanyId(request.getCode(), company.getId())) {
            throw new IllegalArgumentException("Warehouse code already exists for this company: " + request.getCode());
        }

        Warehouse warehouse = new Warehouse();
        warehouse.setCompany(company);
        warehouse.setCode(request.getCode());
        warehouse.setName(request.getName());
        warehouse.setActive(request.isActive());

        Warehouse saved = warehouseRepository.save(warehouse);
        log.info("User '{}' created warehouse '{}' (id={}) for company {}", username, saved.getName(), saved.getId(), company.getId());
        return saved;
    }

    @Transactional
    public Warehouse update(Long id, WarehouseRequest request, String username) {
        Warehouse warehouse = findById(id, username);

        if (StringUtils.hasText(request.getCode())
                && !request.getCode().equals(warehouse.getCode())
                && warehouseRepository.existsByCodeAndCompanyId(request.getCode(), warehouse.getCompany().getId())) {
            throw new IllegalArgumentException("Warehouse code already exists for this company: " + request.getCode());
        }

        warehouse.setCode(request.getCode());
        warehouse.setName(request.getName());
        warehouse.setActive(request.isActive());

        Warehouse saved = warehouseRepository.save(warehouse);
        log.info("User '{}' updated warehouse '{}' (id={})", username, saved.getName(), saved.getId());
        return saved;
    }

    @Transactional
    public void delete(Long id, String username) {
        Warehouse warehouse = findById(id, username);
        warehouseRepository.delete(warehouse);
        log.info("User '{}' deleted warehouse '{}' (id={})", username, warehouse.getName(), id);
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