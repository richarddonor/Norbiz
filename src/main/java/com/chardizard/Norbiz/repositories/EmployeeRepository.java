package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.Employee;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {
    Page<Employee> findByCompanyIdIn(List<Long> companyIds, Pageable pageable);
    boolean existsByEmployeeCodeAndCompanyId(String employeeCode, Long companyId);
}