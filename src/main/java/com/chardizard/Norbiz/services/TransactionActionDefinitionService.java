package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.TransactionActionDefinitionRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import com.chardizard.Norbiz.util.SpecificationUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransactionActionDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionActionDefinitionService.class);

    private final TransactionActionDefinitionRepository definitionRepository;
    private final TransactionEventRepository transactionEventRepository;
    private final CompanyRepository companyRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    public Page<TransactionActionDefinition> findAllForUser(String username, TransactionType transactionType,
                                                            Map<String, String> filters, Boolean active, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<TransactionActionDefinition> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<TransactionActionDefinition> typeScope = transactionType == null ? null
                : (root, query, cb) -> cb.equal(root.get("transactionType"), transactionType);

        Specification<TransactionActionDefinition> spec = SpecificationUtils.allOf(
                companyScope,
                typeScope,
                SpecificationUtils.containsIgnoreCase("code", filters.get("code")),
                SpecificationUtils.containsIgnoreCase("name", filters.get("name")),
                SpecificationUtils.booleanEquals("active", active)
        );

        return definitionRepository.findAll(spec, pageable);
    }

    public TransactionActionDefinition findById(Long id, String username) {
        TransactionActionDefinition definition = definitionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Transaction action definition not found: " + id));
        assertCompanyAccess(username, definition.getCompany().getId());
        return definition;
    }

    @Transactional
    public TransactionActionDefinition create(TransactionActionDefinitionRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        if (definitionRepository.existsByCompanyIdAndTransactionTypeAndCode(company.getId(), request.getTransactionType(), request.getCode())) {
            throw new IllegalArgumentException("Action code already exists for " + request.getTransactionType() + ": " + request.getCode());
        }

        TransactionActionDefinition definition = new TransactionActionDefinition();
        definition.setCompany(company);
        definition.setTransactionType(request.getTransactionType());
        apply(definition, request);

        TransactionActionDefinition saved = definitionRepository.save(definition);
        log.info("User '{}' created transaction action '{}' (id={}) for {} in company {}",
                username, saved.getCode(), saved.getId(), saved.getTransactionType(), company.getId());
        return saved;
    }

    @Transactional
    public TransactionActionDefinition update(Long id, TransactionActionDefinitionRequest request, String username) {
        TransactionActionDefinition definition = findById(id, username);

        // Company and transaction type are fixed: existing history and prerequisite links depend on them.
        if (!definition.getCompany().getId().equals(request.getCompanyId())) {
            throw new IllegalArgumentException("companyId cannot be changed");
        }
        if (definition.getTransactionType() != request.getTransactionType()) {
            throw new IllegalArgumentException("transactionType cannot be changed");
        }
        if (!definition.getCode().equals(request.getCode())
                && definitionRepository.existsByCompanyIdAndTransactionTypeAndCode(
                        definition.getCompany().getId(), definition.getTransactionType(), request.getCode())) {
            throw new IllegalArgumentException("Action code already exists for " + request.getTransactionType() + ": " + request.getCode());
        }

        apply(definition, request);

        TransactionActionDefinition saved = definitionRepository.save(definition);
        log.info("User '{}' updated transaction action '{}' (id={})", username, saved.getCode(), saved.getId());
        return saved;
    }

    @Transactional
    public void delete(Long id, String username) {
        TransactionActionDefinition definition = findById(id, username);

        if (transactionEventRepository.existsByActionDefinitionId(id)) {
            throw new IllegalArgumentException("Action '" + definition.getCode()
                    + "' has already been taken on transactions; deactivate it (active=false) instead of deleting");
        }
        if (definitionRepository.existsByPrerequisitesId(id)) {
            throw new IllegalArgumentException("Action '" + definition.getCode()
                    + "' is a prerequisite of another action; remove it from those prerequisites first");
        }

        definitionRepository.delete(definition);
        log.info("User '{}' deleted transaction action '{}' (id={})", username, definition.getCode(), id);
    }

    private void apply(TransactionActionDefinition definition, TransactionActionDefinitionRequest request) {
        definition.setCode(request.getCode());
        definition.setName(request.getName());
        definition.setSortOrder(request.getSortOrder());
        definition.setActive(request.getActive());

        Set<Role> roles = new HashSet<>(roleRepository.findAllById(request.getAllowedRoleIds()));
        if (roles.size() != request.getAllowedRoleIds().size()) {
            throw new IllegalArgumentException("One or more allowedRoleIds do not exist");
        }
        definition.setAllowedRoles(roles);

        Set<Long> prerequisiteIds = request.getPrerequisiteIds() == null ? Set.of() : request.getPrerequisiteIds();
        if (definition.getId() != null && prerequisiteIds.contains(definition.getId())) {
            throw new IllegalArgumentException("An action cannot be its own prerequisite");
        }
        Set<TransactionActionDefinition> prerequisites = new HashSet<>(definitionRepository.findAllById(prerequisiteIds));
        if (prerequisites.size() != prerequisiteIds.size()) {
            throw new IllegalArgumentException("One or more prerequisiteIds do not exist");
        }
        for (TransactionActionDefinition prerequisite : prerequisites) {
            if (!prerequisite.getCompany().getId().equals(definition.getCompany().getId())
                    || prerequisite.getTransactionType() != definition.getTransactionType()) {
                throw new IllegalArgumentException("Prerequisite '" + prerequisite.getCode()
                        + "' must belong to the same company and transaction type");
            }
        }
        // A brand-new definition can't be reachable from anything yet, so only updates can introduce a cycle.
        if (definition.getId() != null && reaches(prerequisites, definition.getId(), new HashSet<>())) {
            throw new IllegalArgumentException("Prerequisites would create a circular dependency");
        }
        definition.setPrerequisites(prerequisites);
    }

    private boolean reaches(Set<TransactionActionDefinition> from, Long targetId, Set<Long> visited) {
        for (TransactionActionDefinition node : from) {
            if (node.getId().equals(targetId)) return true;
            if (visited.add(node.getId()) && reaches(node.getPrerequisites(), targetId, visited)) return true;
        }
        return false;
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
