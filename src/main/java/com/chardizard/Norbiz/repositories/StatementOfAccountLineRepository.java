package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.StatementOfAccountLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface StatementOfAccountLineRepository extends JpaRepository<StatementOfAccountLine, Long>, JpaSpecificationExecutor<StatementOfAccountLine> {
}
