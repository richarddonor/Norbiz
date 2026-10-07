package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.PullOutReasonRequest;
import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.PullOutReason;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.PullOutReasonRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import com.chardizard.Norbiz.util.ForeignKeyViolations;
import com.chardizard.Norbiz.util.SpecificationUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PullOutReasonService {

    private static final Logger log = LoggerFactory.getLogger(PullOutReasonService.class);

    private final PullOutReasonRepository pullOutReasonRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;

    public Page<PullOutReason> findAllForUser(String username, Map<String, String> filters, Instant updatedAtFrom, Instant updatedAtTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<PullOutReason> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<PullOutReason> spec = SpecificationUtils.allOf(
                companyScope,
                SpecificationUtils.containsIgnoreCase("name", filters.get("name")),
                SpecificationUtils.containsIgnoreCase("company.name", filters.get("company")),
                SpecificationUtils.containsIgnoreCase("createdBy", filters.get("createdBy")),
                SpecificationUtils.booleanEquals("active", filters.get("active")),
                SpecificationUtils.dateRange("updatedAt", updatedAtFrom, updatedAtTo)
        );

        return pullOutReasonRepository.findAll(spec, pageable);
    }

    public PullOutReason findById(Long id, String username) {
        PullOutReason reason = pullOutReasonRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Pull out reason not found: " + id));
        assertCompanyAccess(username, reason.getCompany().getId());
        return reason;
    }

    @Transactional
    public PullOutReason create(PullOutReasonRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        if (pullOutReasonRepository.existsByNameAndCompanyId(request.getName(), company.getId())) {
            throw new IllegalArgumentException("Pull out reason already exists for this company: " + request.getName());
        }

        PullOutReason reason = new PullOutReason();
        reason.setCompany(company);
        apply(reason, request);
        PullOutReason saved = pullOutReasonRepository.save(reason);
        log.info("User '{}' created pull out reason '{}' (id={}) for company {}", username, saved.getName(), saved.getId(), company.getId());
        return saved;
    }

    @Transactional
    public PullOutReason update(Long id, PullOutReasonRequest request, String username) {
        PullOutReason reason = findById(id, username);

        if (!reason.getName().equals(request.getName())
                && pullOutReasonRepository.existsByNameAndCompanyId(request.getName(), reason.getCompany().getId())) {
            throw new IllegalArgumentException("Pull out reason already exists for this company: " + request.getName());
        }

        apply(reason, request);
        PullOutReason saved = pullOutReasonRepository.save(reason);
        log.info("User '{}' updated pull out reason '{}' (id={})", username, saved.getName(), saved.getId());
        return saved;
    }

    // Rejected with ENTITY_IN_USE once any Outlet Pull Out references it — deactivate instead.
    @Transactional
    public void delete(Long id, String username) {
        PullOutReason reason = findById(id, username);
        ForeignKeyViolations.deleteOrThrow(pullOutReasonRepository, reason, "Pull Out Reason", id);
        log.info("User '{}' deleted pull out reason '{}' (id={})", username, reason.getName(), id);
    }

    private void apply(PullOutReason reason, PullOutReasonRequest request) {
        reason.setName(request.getName());
        if (request.getActive() != null) reason.setActive(request.getActive());
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
