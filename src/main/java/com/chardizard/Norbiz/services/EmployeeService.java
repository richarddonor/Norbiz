package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.EmployeeRequest;
import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.Employee;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.EmployeeRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);

    private final EmployeeRepository employeeRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;

    public Page<Employee> findAllForUser(String username, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        if (isSuperAdmin) {
            return employeeRepository.findAll(pageable);
        }

        List<Long> companyIds = user.getCompanies().stream()
                .map(Company::getId)
                .collect(Collectors.toList());

        return companyIds.isEmpty() ? Page.empty(pageable) : employeeRepository.findByCompanyIdIn(companyIds, pageable);
    }

    public Employee findById(Long id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + id));
    }

    @Transactional
    public Employee create(EmployeeRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        if (employeeRepository.existsByEmployeeCodeAndCompanyId(request.getEmployeeCode(), company.getId())) {
            throw new IllegalArgumentException("Employee code already exists for this company: " + request.getEmployeeCode());
        }

        Employee employee = new Employee();
        employee.setCompany(company);
        employee.setEmployeeCode(request.getEmployeeCode());
        employee.setFirstName(request.getFirstName());
        employee.setLastName(request.getLastName());
        employee.setEmail(request.getEmail());
        employee.setPhone(request.getPhone());
        employee.setActive(request.isActive());
        employee.setTags(request.getTags() != null ? request.getTags() : new HashSet<>());

        if (request.getUserId() != null) {
            User user = userRepository.findById(request.getUserId())
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + request.getUserId()));
            employee.setUser(user);
        }

        return employeeRepository.save(employee);
    }

    @Transactional
    public Employee update(Long id, EmployeeRequest request, String username) {
        Employee employee = findById(id);

        assertCompanyAccess(username, employee.getCompany().getId());

        if (!employee.getEmployeeCode().equals(request.getEmployeeCode())
                && employeeRepository.existsByEmployeeCodeAndCompanyId(request.getEmployeeCode(), employee.getCompany().getId())) {
            throw new IllegalArgumentException("Employee code already exists for this company: " + request.getEmployeeCode());
        }

        employee.setEmployeeCode(request.getEmployeeCode());
        employee.setFirstName(request.getFirstName());
        employee.setLastName(request.getLastName());
        employee.setEmail(request.getEmail());
        employee.setPhone(request.getPhone());
        employee.setActive(request.isActive());
        employee.setTags(request.getTags() != null ? request.getTags() : new HashSet<>());

        if (request.getUserId() != null) {
            User user = userRepository.findById(request.getUserId())
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + request.getUserId()));
            employee.setUser(user);
        } else {
            employee.setUser(null);
        }

        return employeeRepository.save(employee);
    }

    @Transactional
    public void delete(Long id, String username) {
        Employee employee = findById(id);
        assertCompanyAccess(username, employee.getCompany().getId());
        employeeRepository.delete(employee);
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