package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.Bank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BankRepository extends JpaRepository<Bank, Long>, JpaSpecificationExecutor<Bank> {

    boolean existsByCodeAndCompanyId(String code, Long companyId);
}
