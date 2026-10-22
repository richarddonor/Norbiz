package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.AccountingSettings;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AccountingSettingsRepository extends JpaRepository<AccountingSettings, Long>, JpaSpecificationExecutor<AccountingSettings> {

    Optional<AccountingSettings> findByCompanyId(Long companyId);
}
