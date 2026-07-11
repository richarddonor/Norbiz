package com.chardizard.Norbiz.repositories;

import com.chardizard.Norbiz.models.DocumentTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplate, Long>, JpaSpecificationExecutor<DocumentTemplate> {
    Optional<DocumentTemplate> findByCompanyIdAndDocumentTypeAndDefaultTemplateTrue(Long companyId, String documentType);
    List<DocumentTemplate> findByCompanyIdAndDocumentTypeAndDefaultTemplateTrueAndIdNot(Long companyId, String documentType, Long id);
}
