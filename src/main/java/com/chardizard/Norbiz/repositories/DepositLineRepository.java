package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.DepositLine;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DepositLineRepository extends JpaRepository<DepositLine, Long>, JpaSpecificationExecutor<DepositLine> {

    // The live (non-voided) deposit line a tender sits on, if any.
    Optional<DepositLine> findFirstByTenderIdAndDepositVoidedFalse(Long tenderId);
}
