package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.BillOfMaterialLineRequest;
import com.chardizard.Norbiz.dto.BillOfMaterialRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.BillOfMaterialRepository;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.ItemRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import com.chardizard.Norbiz.util.ForeignKeyViolations;
import com.chardizard.Norbiz.util.SpecificationUtils;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BillOfMaterialService {

    private static final Logger log = LoggerFactory.getLogger(BillOfMaterialService.class);

    private final BillOfMaterialRepository billOfMaterialRepository;
    private final ItemRepository itemRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;

    public Page<BillOfMaterial> findAllForUser(String username, Map<String, String> filters, Instant updatedAtFrom, Instant updatedAtTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<BillOfMaterial> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<BillOfMaterial> spec = SpecificationUtils.allOf(
                companyScope,
                SpecificationUtils.containsIgnoreCase("code", filters.get("code")),
                SpecificationUtils.anyContainsIgnoreCase(filters.get("item"), "item.itemCode", "item.name"),
                SpecificationUtils.containsIgnoreCase("company.name", filters.get("company")),
                SpecificationUtils.containsIgnoreCase("createdBy", filters.get("createdBy")),
                SpecificationUtils.booleanEquals("active", filters.get("active")),
                SpecificationUtils.dateRange("updatedAt", updatedAtFrom, updatedAtTo)
        );

        return billOfMaterialRepository.findAll(spec, pageable);
    }

    public BillOfMaterial findById(Long id, String username) {
        BillOfMaterial bom = billOfMaterialRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Bill of materials not found: " + id));
        assertCompanyAccess(username, bom.getCompany().getId());
        return bom;
    }

    @Transactional
    public BillOfMaterial create(BillOfMaterialRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        if (billOfMaterialRepository.existsByCodeAndCompanyId(request.getCode(), company.getId())) {
            throw new IllegalArgumentException("Bill of materials code already exists for this company: " + request.getCode());
        }

        BillOfMaterial bom = new BillOfMaterial();
        bom.setCompany(company);
        apply(bom, request);
        BillOfMaterial saved = billOfMaterialRepository.save(bom);
        log.info("User '{}' created bill of materials '{}' (id={}) for item {} with {} component(s)",
                username, saved.getCode(), saved.getId(), saved.getItem().getId(), saved.getComponents().size());
        return saved;
    }

    @Transactional
    public BillOfMaterial update(Long id, BillOfMaterialRequest request, String username) {
        BillOfMaterial bom = findById(id, username);

        if (!bom.getCode().equals(request.getCode())
                && billOfMaterialRepository.existsByCodeAndCompanyId(request.getCode(), bom.getCompany().getId())) {
            throw new IllegalArgumentException("Bill of materials code already exists for this company: " + request.getCode());
        }

        // Components are rewritten wholesale; flush the removals first so the change history sees
        // delete-then-insert in one change set (diffed by line position, see MasterDataHistoryService).
        bom.getComponents().clear();
        entityManager.flush();
        apply(bom, request);
        BillOfMaterial saved = billOfMaterialRepository.save(bom);
        log.info("User '{}' updated bill of materials '{}' (id={}) — {} component(s)",
                username, saved.getCode(), saved.getId(), saved.getComponents().size());
        return saved;
    }

    // Rejected with ENTITY_IN_USE once any Assembly references it — deactivate instead.
    @Transactional
    public void delete(Long id, String username) {
        BillOfMaterial bom = findById(id, username);
        ForeignKeyViolations.deleteOrThrow(billOfMaterialRepository, bom, "Bill of Materials", id);
        log.info("User '{}' deleted bill of materials '{}' (id={})", username, bom.getCode(), id);
    }

    private void apply(BillOfMaterial bom, BillOfMaterialRequest request) {
        Long companyId = bom.getCompany().getId();
        Item output = loadItem(request.getItemId(), companyId);
        // An unchanged output may since have been deactivated; only a newly picked one must be active.
        if (!output.isActive() && (bom.getItem() == null || !bom.getItem().getId().equals(output.getId()))) {
            throw new IllegalArgumentException("Item is inactive: " + output.getItemCode());
        }

        bom.setCode(request.getCode());
        bom.setItem(output);
        if (request.getActive() != null) bom.setActive(request.getActive());

        Set<Long> seen = new HashSet<>();
        int lineNumber = 1;
        for (BillOfMaterialLineRequest lineRequest : request.getComponents()) {
            Item component = loadItem(lineRequest.getItemId(), companyId);
            if (component.getId().equals(output.getId())) {
                throw new IllegalArgumentException("An item can't be a component of its own bill of materials: " + component.getItemCode());
            }
            if (!seen.add(component.getId())) {
                throw new IllegalArgumentException("Component listed more than once: " + component.getItemCode());
            }

            BillOfMaterialLine line = new BillOfMaterialLine();
            line.setBillOfMaterial(bom);
            line.setItem(component);
            line.setQuantity(lineRequest.getQuantity());
            line.setLineNumber(lineNumber++);
            bom.getComponents().add(line);
        }
    }

    private Item loadItem(Long itemId, Long companyId) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));
        if (!item.getCompany().getId().equals(companyId)) {
            throw new IllegalArgumentException("Item does not belong to company: " + companyId);
        }
        if (!item.getTags().contains(ItemTag.INVENTORY)) {
            throw new IllegalArgumentException("Item is not inventory-tracked: " + item.getItemCode());
        }
        return item;
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
