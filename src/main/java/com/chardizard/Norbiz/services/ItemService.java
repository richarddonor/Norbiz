package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.ItemRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.ItemCategoryRepository;
import com.chardizard.Norbiz.repositories.ItemRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import com.chardizard.Norbiz.util.SpecificationUtils;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ItemService {

    private static final Logger log = LoggerFactory.getLogger(ItemService.class);

    private final ItemRepository itemRepository;
    private final CompanyRepository companyRepository;
    private final ItemCategoryRepository itemCategoryRepository;
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

        ItemCategory category = itemCategoryRepository.findById(request.getItemCategoryId())
                .orElseThrow(() -> new IllegalArgumentException("Item category not found: " + request.getItemCategoryId()));

        Item item = new Item();
        item.setCompany(company);
        item.setItemCategory(category);
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

    @Transactional
    public Item update(Long id, ItemRequest request, String username, boolean canViewCostPrice) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + id));

        assertCompanyAccess(username, item.getCompany().getId());

        ItemCategory category = itemCategoryRepository.findById(request.getItemCategoryId())
                .orElseThrow(() -> new IllegalArgumentException("Item category not found: " + request.getItemCategoryId()));

        BigDecimal existingCostPrice = item.getPrices().stream()
                .filter(p -> p.getPriceType() == PriceType.COST_PRICE)
                .map(ItemPrice::getAmount)
                .findFirst()
                .orElse(null);

        item.setItemCategory(category);
        item.setName(request.getName());
        item.setTags(request.getTags() != null ? request.getTags() : new HashSet<>());
        // imagePath is managed exclusively by ItemImageController — do not overwrite here

        item.getSkus().clear();
        item.getPrices().clear();
        // Flush DELETEs to the DB now so the unique constraints don't fire
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
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + id));

        assertCompanyAccess(username, item.getCompany().getId());
        itemRepository.delete(item);
        log.info("User '{}' deleted item '{}' (id={})", username, item.getItemCode(), id);
    }

    private void applySkus(Item item, ItemRequest request) {
        if (request.getSkus() == null) return;
        for (String skuCode : request.getSkus()) {
            ItemSku sku = new ItemSku();
            sku.setItem(item);
            sku.setSkuCode(skuCode);
            item.getSkus().add(sku);
        }
    }

    private void applyPrices(Item item, ItemRequest request, boolean canViewCostPrice, BigDecimal existingCostPrice) {
        boolean costPriceApplied = false;
        if (request.getPrices() != null) {
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
