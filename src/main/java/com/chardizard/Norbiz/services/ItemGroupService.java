package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.ItemGroupRequest;
import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.ItemGroup;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.ItemGroupRepository;
import com.chardizard.Norbiz.repositories.ItemRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import com.chardizard.Norbiz.util.SpecificationUtils;
import com.chardizard.Norbiz.exceptions.EntityInUseException;
import com.chardizard.Norbiz.util.ForeignKeyViolations;
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
public class ItemGroupService {

    private static final Logger log = LoggerFactory.getLogger(ItemGroupService.class);

    private final ItemGroupRepository itemGroupRepository;
    private final ItemRepository itemRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;

    public Page<ItemGroup> findAllForUser(String username, Map<String, String> filters, Instant updatedAtFrom, Instant updatedAtTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<ItemGroup> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<ItemGroup> spec = SpecificationUtils.allOf(
                companyScope,
                SpecificationUtils.containsIgnoreCase("name", filters.get("name")),
                SpecificationUtils.containsIgnoreCase("description", filters.get("description")),
                SpecificationUtils.containsIgnoreCase("bnInitials", filters.get("bnInitials")),
                SpecificationUtils.containsIgnoreCase("company.name", filters.get("company")),
                SpecificationUtils.containsIgnoreCase("createdBy", filters.get("createdBy")),
                SpecificationUtils.booleanEquals("active", filters.get("active")),
                SpecificationUtils.dateRange("updatedAt", updatedAtFrom, updatedAtTo)
        );

        return itemGroupRepository.findAll(spec, pageable);
    }

    public ItemGroup findById(Long id, String username) {
        ItemGroup group = itemGroupRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Item group not found: " + id));
        assertCompanyAccess(username, group.getCompany().getId());
        return group;
    }

    @Transactional
    public ItemGroup create(ItemGroupRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        if (itemGroupRepository.existsByNameAndCompanyId(request.getName(), company.getId())) {
            throw new IllegalArgumentException("Item group name already exists for this company: " + request.getName());
        }

        ItemGroup group = new ItemGroup();
        group.setCompany(company);
        apply(group, request);
        ItemGroup saved = itemGroupRepository.save(group);
        log.info("User '{}' created item group '{}' (id={}) for company {}", username, saved.getName(), saved.getId(), company.getId());
        return saved;
    }

    @Transactional
    public ItemGroup update(Long id, ItemGroupRequest request, String username) {
        ItemGroup group = findById(id, username);

        if (!group.getName().equals(request.getName())
                && itemGroupRepository.existsByNameAndCompanyId(request.getName(), group.getCompany().getId())) {
            throw new IllegalArgumentException("Item group name already exists for this company: " + request.getName());
        }

        apply(group, request);
        ItemGroup saved = itemGroupRepository.save(group);
        log.info("User '{}' updated item group '{}' (id={})", username, saved.getName(), saved.getId());
        return saved;
    }

    @Transactional
    public void delete(Long id, String username) {
        ItemGroup group = findById(id, username);
        if (itemRepository.existsByItemGroupId(id)) {
            throw new EntityInUseException("Item Group", id, "Item",
                    "Item group '" + group.getName() + "' is still assigned to items; reassign them or deactivate the group instead");
        }
        ForeignKeyViolations.deleteOrThrow(itemGroupRepository, group, "Item Group", id);
        log.info("User '{}' deleted item group '{}' (id={})", username, group.getName(), id);
    }

    private void apply(ItemGroup group, ItemGroupRequest request) {
        group.setName(request.getName());
        group.setDescription(request.getDescription());
        group.setBnInitials(request.getBnInitials());
        group.setCommissionRate(request.getCommissionRate());
        group.setFocalCommissionRate(request.getFocalCommissionRate());
        if (request.getActive() != null) group.setActive(request.getActive());
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
