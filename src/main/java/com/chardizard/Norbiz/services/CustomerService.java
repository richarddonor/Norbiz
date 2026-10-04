package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.CustomerRequest;
import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.Customer;
import com.chardizard.Norbiz.models.CustomerType;
import com.chardizard.Norbiz.models.Warehouse;
import com.chardizard.Norbiz.models.User;
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
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerRepository customerRepository;
    private final CompanyRepository companyRepository;
    private final WarehouseRepository warehouseRepository;
    private final UserRepository userRepository;

    public Page<Customer> findAllForUser(String username, Map<String, String> filters, Instant updatedAtFrom, Instant updatedAtTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<Customer> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<Customer> spec = SpecificationUtils.allOf(
                companyScope,
                SpecificationUtils.containsIgnoreCase("name", filters.get("name")),
                SpecificationUtils.containsIgnoreCase("code", filters.get("code")),
                SpecificationUtils.containsIgnoreCase("type", filters.get("type")),
                SpecificationUtils.containsIgnoreCase("company.name", filters.get("company")),
                SpecificationUtils.containsIgnoreCase("createdBy", filters.get("createdBy")),
                SpecificationUtils.booleanEquals("active", filters.get("active")),
                SpecificationUtils.dateRange("updatedAt", updatedAtFrom, updatedAtTo)
        );

        return customerRepository.findAll(spec, pageable);
    }

    public Customer findById(Long id, String username) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + id));
        assertCompanyAccess(username, customer.getCompany().getId());
        return customer;
    }

    @Transactional
    public Customer create(CustomerRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        if (StringUtils.hasText(request.getCode())
                && customerRepository.existsByCodeAndCompanyId(request.getCode(), company.getId())) {
            throw new IllegalArgumentException("Customer code already exists for this company: " + request.getCode());
        }

        Customer customer = new Customer();
        customer.setCompany(company);
        customer.setCode(request.getCode());
        customer.setName(request.getName());
        customer.setType(request.getType());
        customer.setEmail(request.getEmail());
        customer.setPhone(request.getPhone());
        customer.setActive(request.isActive());
        if (customer.getType() == CustomerType.OUTLET) {
            customer.setWarehouse(createOutletWarehouse(customer, username));
        }

        Customer saved = customerRepository.save(customer);
        log.info("User '{}' created customer '{}' (id={}) for company {}", username, saved.getName(), saved.getId(), company.getId());
        return saved;
    }

    @Transactional
    public Customer update(Long id, CustomerRequest request, String username) {
        Customer customer = findById(id, username);

        if (StringUtils.hasText(request.getCode())
                && !request.getCode().equals(customer.getCode())
                && customerRepository.existsByCodeAndCompanyId(request.getCode(), customer.getCompany().getId())) {
            throw new IllegalArgumentException("Customer code already exists for this company: " + request.getCode());
        }

        // The outlet warehouse may hold stock or in-transit deliveries; turning the outlet back into a
        // plain customer would orphan them, so the type is locked once the warehouse exists.
        if (customer.getWarehouse() != null && request.getType() != CustomerType.OUTLET) {
            throw new IllegalArgumentException("Cannot change an outlet with its own warehouse back to " + request.getType()
                    + ": " + customer.getName());
        }

        customer.setCode(request.getCode());
        customer.setName(request.getName());
        customer.setType(request.getType());
        customer.setEmail(request.getEmail());
        customer.setPhone(request.getPhone());
        customer.setActive(request.isActive());

        if (customer.getType() == CustomerType.OUTLET) {
            if (customer.getWarehouse() == null) {
                customer.setWarehouse(createOutletWarehouse(customer, username));
            } else {
                syncOutletWarehouse(customer, username);
            }
        }

        Customer saved = customerRepository.save(customer);
        log.info("User '{}' updated customer '{}' (id={})", username, saved.getName(), saved.getId());
        return saved;
    }

    @Transactional
    public void delete(Long id, String username) {
        Customer customer = findById(id, username);
        Warehouse outletWarehouse = customer.getWarehouse();
        ForeignKeyViolations.deleteOrThrow(customerRepository, customer, "Customer", id);
        // The outlet warehouse goes with its customer; still-referenced stock/transactions surface as ENTITY_IN_USE
        // and roll back the customer delete too.
        if (outletWarehouse != null) {
            ForeignKeyViolations.deleteOrThrow(warehouseRepository, outletWarehouse, "Warehouse", outletWarehouse.getId());
        }
        log.info("User '{}' deleted customer '{}' (id={})", username, customer.getName(), id);
    }

    /**
     * Creates the OUTLET customer's own warehouse — it gets its own warehouse identity id (not the
     * customer id) and mirrors the customer's name/code/active. Also used by OutletWarehouseProvisioner
     * for outlets created before this link existed. Not @Transactional on purpose: callers already run in a
     * transaction, and a validation failure here must not mark that transaction rollback-only for the provisioner.
     */
    public Warehouse createOutletWarehouse(Customer customer, String username) {
        Long companyId = customer.getCompany().getId();
        if (StringUtils.hasText(customer.getCode())
                && warehouseRepository.existsByCodeAndCompanyId(customer.getCode(), companyId)) {
            throw new IllegalArgumentException("Cannot create the outlet's warehouse: warehouse code already exists for this company: "
                    + customer.getCode());
        }
        Warehouse warehouse = new Warehouse();
        warehouse.setCompany(customer.getCompany());
        warehouse.setCode(customer.getCode());
        warehouse.setName(customer.getName());
        warehouse.setActive(customer.isActive());
        warehouse.setOutlet(true);
        Warehouse saved = warehouseRepository.save(warehouse);
        log.info("User '{}' created outlet warehouse '{}' (id={}) for customer '{}' in company {}",
                username, saved.getName(), saved.getId(), customer.getName(), companyId);
        return saved;
    }

    private void syncOutletWarehouse(Customer customer, String username) {
        Warehouse warehouse = customer.getWarehouse();
        if (StringUtils.hasText(customer.getCode())
                && !customer.getCode().equals(warehouse.getCode())
                && warehouseRepository.existsByCodeAndCompanyId(customer.getCode(), customer.getCompany().getId())) {
            throw new IllegalArgumentException("Cannot rename the outlet's warehouse: warehouse code already exists for this company: "
                    + customer.getCode());
        }
        warehouse.setCode(customer.getCode());
        warehouse.setName(customer.getName());
        warehouse.setActive(customer.isActive());
        warehouseRepository.save(warehouse);
        log.info("User '{}' synced outlet warehouse (id={}) with customer '{}'", username, warehouse.getId(), customer.getName());
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