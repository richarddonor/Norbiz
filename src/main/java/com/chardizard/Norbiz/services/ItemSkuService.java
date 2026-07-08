package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.ItemSkuRequest;
import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.Item;
import com.chardizard.Norbiz.models.ItemSku;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.ItemRepository;
import com.chardizard.Norbiz.repositories.ItemSkuRepository;
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

@Service
@RequiredArgsConstructor
public class ItemSkuService {

    private static final Logger log = LoggerFactory.getLogger(ItemSkuService.class);

    private final ItemSkuRepository itemSkuRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;

    public Page<ItemSku> findAll(String username, Map<String, String> filters, Pageable pageable) {
        User user = getUser(username);

        Specification<ItemSku> companyScope = null;
        if (!isSuperAdmin(user)) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .toList();
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("item").get("company").get("id").in(companyIds);
        }

        Specification<ItemSku> spec = SpecificationUtils.allOf(
                companyScope,
                SpecificationUtils.containsIgnoreCase("skuCode", filters.get("skuCode")),
                SpecificationUtils.containsIgnoreCase("item.itemCode", filters.get("itemCode")),
                SpecificationUtils.containsIgnoreCase("item.name", filters.get("itemName")),
                SpecificationUtils.containsIgnoreCase("unitPrice", filters.get("unitPrice"))
        );

        return itemSkuRepository.findAll(spec, pageable);
    }

    public ItemSku findById(Long id, String username) {
        ItemSku sku = itemSkuRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("SKU not found: " + id));
        assertCompanyAccess(username, sku.getItem().getCompany().getId());
        return sku;
    }

    @Transactional
    public ItemSku create(ItemSkuRequest request, String username) {
        if (request.getItemId() == null) {
            throw new IllegalArgumentException("itemId is required when creating a SKU");
        }
        if (request.getSkuCode() == null || request.getSkuCode().isBlank()) {
            throw new IllegalArgumentException("skuCode is required");
        }
        if (request.getUnitPrice() == null) {
            throw new IllegalArgumentException("unitPrice is required");
        }

        Item item = itemRepository.findById(request.getItemId())
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + request.getItemId()));

        assertCompanyAccess(username, item.getCompany().getId());

        if (itemSkuRepository.existsBySkuCode(request.getSkuCode())) {
            throw new IllegalArgumentException("SKU code already exists: " + request.getSkuCode());
        }

        ItemSku sku = new ItemSku();
        sku.setItem(item);
        sku.setSkuCode(request.getSkuCode());
        sku.setUnitPrice(request.getUnitPrice());

        return itemSkuRepository.save(sku);
    }

    @Transactional
    public ItemSku update(Long id, ItemSkuRequest request, String username) {
        if (request.getSkuCode() == null || request.getSkuCode().isBlank()) {
            throw new IllegalArgumentException("skuCode is required");
        }
        if (request.getUnitPrice() == null) {
            throw new IllegalArgumentException("unitPrice is required");
        }

        ItemSku sku = itemSkuRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("SKU not found: " + id));

        assertCompanyAccess(username, sku.getItem().getCompany().getId());

        if (!sku.getSkuCode().equals(request.getSkuCode())
                && itemSkuRepository.existsBySkuCode(request.getSkuCode())) {
            throw new IllegalArgumentException("SKU code already exists: " + request.getSkuCode());
        }

        sku.setSkuCode(request.getSkuCode());
        sku.setUnitPrice(request.getUnitPrice());

        return itemSkuRepository.save(sku);
    }

    @Transactional
    public void delete(Long id, String username) {
        ItemSku sku = itemSkuRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("SKU not found: " + id));

        assertCompanyAccess(username, sku.getItem().getCompany().getId());
        itemSkuRepository.delete(sku);
    }

    private void assertCompanyAccess(String username, Long companyId) {
        User user = getUser(username);

        if (isSuperAdmin(user)) return;

        boolean hasAccess = user.getCompanies().stream()
                .anyMatch(c -> c.getId().equals(companyId));

        if (!hasAccess) {
            log.warn("User '{}' denied access to company {}", username, companyId);
            throw new SecurityException("Access denied to company: " + companyId);
        }
    }

    private User getUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
    }

    private boolean isSuperAdmin(User user) {
        return user.getRoles().stream().anyMatch(r -> r.getName().equals("SUPER_ADMIN"));
    }
}
