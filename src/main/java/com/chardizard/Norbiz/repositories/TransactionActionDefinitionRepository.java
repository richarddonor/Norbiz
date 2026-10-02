package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.TransactionActionDefinition;
import com.chardizard.Norbiz.models.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface TransactionActionDefinitionRepository extends JpaRepository<TransactionActionDefinition, Long>,
        JpaSpecificationExecutor<TransactionActionDefinition> {
    boolean existsByCompanyIdAndTransactionTypeAndCode(Long companyId, TransactionType transactionType, String code);
    List<TransactionActionDefinition> findByCompanyIdAndTransactionTypeAndActiveTrueOrderBySortOrderAscNameAsc(
            Long companyId, TransactionType transactionType);
    boolean existsByPrerequisitesId(Long prerequisiteId);
}
