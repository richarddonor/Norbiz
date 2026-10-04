package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.WarehouseRequest;
import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.models.Warehouse;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.CustomerRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import com.chardizard.Norbiz.repositories.WarehouseRepository;
import com.chardizard.Norbiz.util.SpecificationUtils;
import com.chardizard.Norbiz.util.ForeignKeyViolations;
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
    private final CustomerRepository customerRepository;
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
        applyMain(warehouse, request.isMain(), username);

        Warehouse saved = warehouseRepository.save(warehouse);
        log.info("User '{}' created warehouse '{}' (id={}) for company {}", username, saved.getName(), saved.getId(), company.getId());
        return saved;
    }

    @Transactional
    public Warehouse update(Long id, WarehouseRequest request, String username) {
        Warehouse warehouse = findById(id, username);
        assertNotOutlet(warehouse, "updated");

        if (StringUtils.hasText(request.getCode())
                && !request.getCode().equals(warehouse.getCode())
                && warehouseRepository.existsByCodeAndCompanyId(request.getCode(), warehouse.getCompany().getId())) {
            throw new IllegalArgumentException("Warehouse code already exists for this company: " + request.getCode());
        }

        warehouse.setCode(request.getCode());
        warehouse.setName(request.getName());
        warehouse.setActive(request.isActive());
        applyMain(warehouse, request.isMain(), username);

        Warehouse saved = warehouseRepository.save(warehouse);
        log.info("User '{}' updated warehouse '{}' (id={})", username, saved.getName(), saved.getId());
        return saved;
    }

    @Transactional
    public void delete(Long id, String username) {
        Warehouse warehouse = findById(id, username);
        assertNotOutlet(warehouse, "deleted");
        ForeignKeyViolations.deleteOrThrow(warehouseRepository, warehouse, "Warehouse", id);
        log.info("User '{}' deleted warehouse '{}' (id={})", username, warehouse.getName(), id);
    }

    // At most one main warehouse per company (the Delivery Receipt source). Making this one main
    // demotes the current one through JPA — not a bulk UPDATE — so the cache invalidation listener fires.
    private void applyMain(Warehouse warehouse, boolean main, String username) {
        if (main) {
            if (warehouse.isOutlet()) {
                throw new IllegalArgumentException("An outlet warehouse cannot be the main warehouse");
            }
            if (!warehouse.isActive()) {
                throw new IllegalArgumentException("An inactive warehouse cannot be the main warehouse");
            }
            warehouseRepository.findFirstByCompanyIdAndMainTrue(warehouse.getCompany().getId())
                    .filter(current -> !current.getId().equals(warehouse.getId()))
                    .ifPresent(current -> {
                        current.setMain(false);
                        warehouseRepository.saveAndFlush(current);
                        log.info("User '{}' unset main warehouse '{}' (id={}) for company {}",
                                username, current.getName(), current.getId(), current.getCompany().getId());
                    });
        }
        warehouse.setMain(main);
    }

    // Outlet warehouses are created and kept in sync by CustomerService — editing them here would drift
    // them from their customer, and deleting one would strand the customer's link.
    private void assertNotOutlet(Warehouse warehouse, String action) {
        if (!warehouse.isOutlet()) return;
        String owner = customerRepository.findByWarehouseId(warehouse.getId())
                .map(c -> " '" + c.getName() + "'").orElse("");
        throw new IllegalArgumentException("Outlet warehouse '" + warehouse.getName() + "' is managed through its outlet customer"
                + owner + " and cannot be " + action + " directly");
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