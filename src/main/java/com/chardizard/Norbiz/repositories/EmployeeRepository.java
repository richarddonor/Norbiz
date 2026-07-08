package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.Employee;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    Page<Employee> findByCompanyIdIn(List<Long> companyIds, Pageable pageable);
    boolean existsByEmployeeCodeAndCompanyId(String employeeCode, Long companyId);
}