package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.ItemRequest;
import com.chardizard.Norbiz.dto.ItemSkuLineRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.ItemCategoryRepository;
import com.chardizard.Norbiz.repositories.ItemGroupRepository;
import com.chardizard.Norbiz.repositories.ItemRepository;
import com.chardizard.Norbiz.repositories.ItemSkuRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import com.chardizard.Norbiz.util.SpecificationUtils;
import com.chardizard.Norbiz.util.ForeignKeyViolations;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ItemService {

    private static final Logger log = LoggerFactory.getLogger(ItemService.class);

    private final ItemRepository itemRepository;
    private final ItemSkuRepository itemSkuRepository;
    private final CompanyRepository companyRepository;
    private final ItemCategoryRepository itemCategoryRepository;
    private final ItemGroupRepository itemGroupRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;

    public Page<Item> findAllForUser(String username, Map<String, String> filters, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<Item> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<Item> spec = SpecificationUtils.allOf(
                companyScope,
                SpecificationUtils.containsIgnoreCase("itemCode", filters.get("itemCode")),
                SpecificationUtils.containsIgnoreCase("name", filters.get("name")),
                SpecificationUtils.containsIgnoreCase("itemCategory.name", filters.get("category")),
                SpecificationUtils.containsIgnoreCase("itemGroup.name", filters.get("group")),
                SpecificationUtils.containsIgnoreCase("company.name", filters.get("company")),
                SpecificationUtils.containsIgnoreCase("skus.skuCode", filters.get("skus")),
                unitPriceContains(filters.get("unitPrice"))
        );

        return itemRepository.findAll(spec, pageable);
    }

    /** Item.unitPrice isn't a direct column — it's the amount of whichever
     * child ItemPrice row has priceType == UNIT_PRICE. */
    private Specification<Item> unitPriceContains(String value) {
        if (!StringUtils.hasText(value)) return null;
        String pattern = "%" + value.trim().toLowerCase() + "%";
        return (root, query, cb) -> {
            query.distinct(true);
            Join<Item, ItemPrice> prices = root.join("prices", JoinType.LEFT);
            return cb.and(
                    cb.equal(prices.get("priceType"), PriceType.UNIT_PRICE),
                    cb.like(cb.lower(prices.get("amount").as(String.class)), pattern)
            );
        };
    }

    @Transactional
    public Item create(ItemRequest request, String username, boolean canViewCostPrice) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        if (itemRepository.existsByCompanyIdAndItemCode(company.getId(), request.getItemCode())) {
            throw new IllegalArgumentException("Item code already exists for this company");
        }

        ItemCategory category = loadCategoryForCompany(request.getItemCategoryId(), company.getId());
        ItemGroup group = loadGroupForCompany(request.getItemGroupId(), company.getId(), null);

        Item item = new Item();
        item.setCompany(company);
        item.setItemCategory(category);
        item.setItemGroup(group);
        item.setItemCode(request.getItemCode());
        item.setName(request.getName());
        item.setImagePath(request.getImagePath());
        item.setTags(request.getTags() != null ? request.getTags() : new HashSet<>());
        applySkus(item, request);
        applyPrices(item, request, canViewCostPrice, null);

        Item saved = itemRepository.save(item);
        log.info("User '{}' created item '{}' (id={}) for company {}", username, saved.getItemCode(), saved.getId(), company.getId());
        return saved;
    }

    public Item findById(Long id, String username) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + id));
        assertCompanyAccess(username, item.getCompany().getId());
        return item;
    }

    @Transactional
    public Item update(Long id, ItemRequest request, String username, boolean canViewCostPrice) {
        Item item = findById(id, username);

        ItemCategory category = loadCategoryForCompany(request.getItemCategoryId(), item.getCompany().getId());
        ItemGroup group = loadGroupForCompany(request.getItemGroupId(), item.getCompany().getId(), item.getItemGroup());

        BigDecimal existingCostPrice = item.getPrices().stream()
                .filter(p -> p.getPriceType() == PriceType.COST_PRICE)
                .map(ItemPrice::getAmount)
                .findFirst()
                .orElse(null);

        item.setItemCategory(category);
        item.setItemGroup(group);
        item.setName(request.getName());
        item.setTags(request.getTags() != null ? request.getTags() : new HashSet<>());
        // imagePath is managed exclusively by ItemImageController — do not overwrite here

        item.getPrices().clear();
        // Flush the price DELETEs now so ITEM_PRICES_ITEM_PRICE_TYPE_UQ doesn't fire
        // when the new rows are inserted below in the same transaction.
        entityManager.flush();
        applySkus(item, request);
        applyPrices(item, request, canViewCostPrice, existingCostPrice);

        Item saved = itemRepository.save(item);
        log.info("User '{}' updated item '{}' (id={})", username, saved.getItemCode(), saved.getId());
        return saved;
    }

    @Transactional
    public void delete(Long id, String username) {
        Item item = findById(id, username);
        ForeignKeyViolations.deleteOrThrow(itemRepository, item, "Item", id);
        log.info("User '{}' deleted item '{}' (id={})", username, item.getItemCode(), id);
    }

    // Reconciles the item's SKUs with request.skuLines by code, rather than deleting and re-inserting
    // them: a kept SKU keeps its row (and id), only its unit price is updated. Null leaves them as-is,
    // so a client that doesn't edit SKUs can't wipe the ones managed on the Item SKUs page.
    private void applySkus(Item item, ItemRequest request) {
        if (request.getSkuLines() == null) return;

        Map<String, ItemSkuLineRequest> lines = new LinkedHashMap<>();
        for (ItemSkuLineRequest line : request.getSkuLines()) {
            String code = line.getSkuCode().trim();
            if (lines.putIfAbsent(code, line) != null) {
                throw new IllegalArgumentException("Duplicate SKU code: " + code);
            }
        }

        // orphanRemoval deletes the SKUs dropped from the list.
        item.getSkus().removeIf(sku -> !lines.containsKey(sku.getSkuCode()));
        Map<String, ItemSku> existing = item.getSkus().stream()
                .collect(Collectors.toMap(ItemSku::getSkuCode, sku -> sku));

        lines.forEach((code, line) -> {
            ItemSku sku = existing.get(code);
            if (sku == null) {
                // Another item may use the same code; duplicates within this item are rejected above.
                sku = new ItemSku();
                sku.setItem(item);
                sku.setSkuCode(code);
                item.getSkus().add(sku);
            }
            sku.setUnitPrice(line.getUnitPrice());
        });
    }

    private void applyPrices(Item item, ItemRequest request, boolean canViewCostPrice, BigDecimal existingCostPrice) {
        boolean costPriceApplied = false;
        if (request.getPrices() != null) {
            Set<PriceType> seen = EnumSet.noneOf(PriceType.class);
            for (var priceReq : request.getPrices()) {
                if (!seen.add(priceReq.getPriceType())) {
                    throw new IllegalArgumentException("Duplicate price type: " + priceReq.getPriceType());
                }
            }
            for (var priceReq : request.getPrices()) {
                ItemPrice price = new ItemPrice();
                price.setItem(item);
                price.setPriceType(priceReq.getPriceType());
                if (priceReq.getPriceType() == PriceType.COST_PRICE && !canViewCostPrice) {
                    // Cost price is sensitive: a caller without VIEW_COST_PRICE cannot change it.
                    BigDecimal enforcedAmount = existingCostPrice != null ? existingCostPrice : BigDecimal.ZERO;
                    if (priceReq.getAmount() != null && priceReq.getAmount().compareTo(enforcedAmount) != 0) {
                        log.warn("Ignored attempt to change COST_PRICE to {} on item without VIEW_COST_PRICE authority; keeping {}",
                                priceReq.getAmount(), enforcedAmount);
                    }
                    price.setAmount(enforcedAmount);
                    costPriceApplied = true;
                } else {
                    price.setAmount(priceReq.getAmount());
                    if (priceReq.getPriceType() == PriceType.COST_PRICE) {
                        costPriceApplied = true;
                    }
                }
                item.getPrices().add(price);
            }
        }

        // Preserve the existing cost price even if the request omitted it entirely.
        if (!canViewCostPrice && !costPriceApplied && existingCostPrice != null) {
            ItemPrice price = new ItemPrice();
            price.setItem(item);
            price.setPriceType(PriceType.COST_PRICE);
            price.setAmount(existingCostPrice);
            item.getPrices().add(price);
        }
    }

    private ItemCategory loadCategoryForCompany(Long categoryId, Long companyId) {
        ItemCategory category = itemCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("Item category not found: " + categoryId));
        if (!category.getCompany().getId().equals(companyId)) {
            throw new IllegalArgumentException("Item category does not belong to company: " + companyId);
        }
        return category;
    }

    /** An inactive group can't be newly assigned, but an item already in it may keep it. */
    private ItemGroup loadGroupForCompany(Long groupId, Long companyId, ItemGroup currentGroup) {
        if (groupId == null) return null;
        ItemGroup group = itemGroupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Item group not found: " + groupId));
        if (!group.getCompany().getId().equals(companyId)) {
            throw new IllegalArgumentException("Item group does not belong to company: " + companyId);
        }
        boolean unchanged = currentGroup != null && currentGroup.getId().equals(groupId);
        if (!group.isActive() && !unchanged) {
            throw new IllegalArgumentException("Item group is inactive: " + group.getName());
        }
        return group;
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
