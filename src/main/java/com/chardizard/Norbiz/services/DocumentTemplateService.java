package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.DocumentTemplateRequest;
import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.DocumentTemplate;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.DocumentTemplateRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import com.chardizard.Norbiz.util.SpecificationUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DocumentTemplateService {

    private static final Logger log = LoggerFactory.getLogger(DocumentTemplateService.class);

    private final DocumentTemplateRepository documentTemplateRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;

    public Page<DocumentTemplate> findAllForUser(String username, Map<String, String> filters, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<DocumentTemplate> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<DocumentTemplate> spec = SpecificationUtils.allOf(
                companyScope,
                SpecificationUtils.containsIgnoreCase("name", filters.get("name")),
                SpecificationUtils.containsIgnoreCase("documentType", filters.get("documentType"))
        );

        return documentTemplateRepository.findAll(spec, pageable);
    }

    public DocumentTemplate findById(Long id, String username) {
        DocumentTemplate template = documentTemplateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document template not found: " + id));
        assertCompanyAccess(username, template.getCompany().getId());
        return template;
    }

    public DocumentTemplate findDefault(String username, Long companyId, String documentType) {
        assertCompanyAccess(username, companyId);
        return documentTemplateRepository.findByCompanyIdAndDocumentTypeAndDefaultTemplateTrue(companyId, documentType)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No default template found for company " + companyId + " and document type " + documentType));
    }

    @Transactional
    public DocumentTemplate create(DocumentTemplateRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        DocumentTemplate template = new DocumentTemplate();
        template.setCompany(company);
        template.setDocumentType(request.getDocumentType());
        template.setName(request.getName());
        template.setLayout(request.getLayout());
        template.setActive(request.isActive());
        template.setDefaultTemplate(request.isDefaultTemplate());

        DocumentTemplate saved = documentTemplateRepository.save(template);
        if (saved.isDefaultTemplate()) {
            clearOtherDefaults(company.getId(), saved.getDocumentType(), saved.getId());
        }

        log.info("User '{}' created document template '{}' (id={}) for company {}", username, saved.getName(), saved.getId(), company.getId());
        return saved;
    }

    @Transactional
    public DocumentTemplate update(Long id, DocumentTemplateRequest request, String username) {
        DocumentTemplate template = findById(id, username);

        template.setDocumentType(request.getDocumentType());
        template.setName(request.getName());
        template.setLayout(request.getLayout());
        template.setActive(request.isActive());
        template.setDefaultTemplate(request.isDefaultTemplate());

        DocumentTemplate saved = documentTemplateRepository.save(template);
        if (saved.isDefaultTemplate()) {
            clearOtherDefaults(saved.getCompany().getId(), saved.getDocumentType(), saved.getId());
        }

        log.info("User '{}' updated document template '{}' (id={})", username, saved.getName(), saved.getId());
        return saved;
    }

    @Transactional
    public void delete(Long id, String username) {
        DocumentTemplate template = findById(id, username);
        documentTemplateRepository.delete(template);
        log.info("User '{}' deleted document template '{}' (id={})", username, template.getName(), id);
    }

    // Only one template may be the default per (company, documentType).
    private void clearOtherDefaults(Long companyId, String documentType, Long keepId) {
        List<DocumentTemplate> others = documentTemplateRepository
                .findByCompanyIdAndDocumentTypeAndDefaultTemplateTrueAndIdNot(companyId, documentType, keepId);
        for (DocumentTemplate other : others) {
            other.setDefaultTemplate(false);
            documentTemplateRepository.save(other);
        }
    }

    private void assertCompanyAccess(String username, Long companyId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        if (isSuperAdmin) return;

        boolean hasAccess = user.getCompanies().stream()
                .anyMatch(c -> c.getId().equals(companyId));

        if (!hasAccess) {
            log.warn("User '{}' denied access to company {}", username, companyId);
            throw new SecurityException("Access denied to company: " + companyId);
        }
    }
}
