package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.StatementOfAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface StatementOfAccountRepository extends JpaRepository<StatementOfAccount, Long>, JpaSpecificationExecutor<StatementOfAccount> {
}
