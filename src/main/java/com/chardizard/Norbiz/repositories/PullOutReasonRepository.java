package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.PullOutReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PullOutReasonRepository extends JpaRepository<PullOutReason, Long>, JpaSpecificationExecutor<PullOutReason> {
    boolean existsByNameAndCompanyId(String name, Long companyId);
}
