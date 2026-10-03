package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.DocumentTemplate;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.DocumentTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guarantees every company has a default print template for every document type in
 * DocumentSchemaRegistry. Only fills gaps — a (company, documentType) that already has a
 * default (hand-designed or previously generated) is never touched, so customised templates
 * survive restarts. Runs on startup via DataInitializer; any future company-creation path
 * must call {@link #ensureDefaults(Company)} too.
 */
@Service
@RequiredArgsConstructor
public class DefaultDocumentTemplateProvisioner {

    private static final Logger log = LoggerFactory.getLogger(DefaultDocumentTemplateProvisioner.class);

    private final DocumentSchemaRegistry schemaRegistry;
    private final DefaultDocumentTemplateFactory templateFactory;
    private final DocumentTemplateRepository documentTemplateRepository;
    private final CompanyRepository companyRepository;

    @Transactional
    public void ensureDefaultsForAllCompanies() {
        companyRepository.findAll().forEach(this::ensureDefaults);
    }

    @Transactional
    public void ensureDefaults(Company company) {
        for (String documentType : schemaRegistry.documentTypes()) {
            boolean hasDefault = documentTemplateRepository
                    .findByCompanyIdAndDocumentTypeAndDefaultTemplateTrue(company.getId(), documentType)
                    .isPresent();
            if (hasDefault) continue;

            DocumentTemplate template = new DocumentTemplate();
            template.setCompany(company);
            template.setDocumentType(documentType);
            template.setName(templateFactory.templateName(documentType));
            template.setLayout(templateFactory.buildLayoutJson(documentType));
            template.setDefaultTemplate(true);
            template.setActive(true);
            DocumentTemplate saved = documentTemplateRepository.save(template);

            log.info("Generated default document template '{}' (id={}) for company {} and document type {}",
                    saved.getName(), saved.getId(), company.getId(), documentType);
        }
    }
}
