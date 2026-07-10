package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.CustomerRequest;
import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.Customer;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.CustomerRepository;
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
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerRepository customerRepository;
    private final CompanyRepository companyRepository;
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
                SpecificationUtils.containsIgnoreCase("active", filters.get("active")),
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

        customer.setCode(request.getCode());
        customer.setName(request.getName());
        customer.setType(request.getType());
        customer.setEmail(request.getEmail());
        customer.setPhone(request.getPhone());
        customer.setActive(request.isActive());

        Customer saved = customerRepository.save(customer);
        log.info("User '{}' updated customer '{}' (id={})", username, saved.getName(), saved.getId());
        return saved;
    }

    @Transactional
    public void delete(Long id, String username) {
        Customer customer = findById(id, username);
        customerRepository.delete(customer);
        log.info("User '{}' deleted customer '{}' (id={})", username, customer.getName(), id);
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