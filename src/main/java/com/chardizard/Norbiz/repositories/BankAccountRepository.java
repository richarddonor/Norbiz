package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long>, JpaSpecificationExecutor<BankAccount> {

    boolean existsByAccountNumberAndCompanyId(String accountNumber, Long companyId);
}
