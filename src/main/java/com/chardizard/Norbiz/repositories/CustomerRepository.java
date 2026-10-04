package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.Customer;
import com.chardizard.Norbiz.models.CustomerType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CustomerRepository extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {
    boolean existsByCodeAndCompanyId(String code, Long companyId);

    Optional<Customer> findByWarehouseId(Long warehouseId);

    List<Customer> findByTypeAndWarehouseIsNull(CustomerType type);
}