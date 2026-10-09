package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.ItemSkuRequest;
import com.chardizard.Norbiz.models.Brand;
import com.chardizard.Norbiz.models.Company;
import com.chardizard.Norbiz.models.Item;
import com.chardizard.Norbiz.models.ItemCategory;
import com.chardizard.Norbiz.models.ItemSku;
import com.chardizard.Norbiz.models.PriceType;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.repositories.BrandRepository;
import com.chardizard.Norbiz.repositories.CompanyRepository;
import com.chardizard.Norbiz.repositories.ItemCategoryRepository;
import com.chardizard.Norbiz.repositories.ItemRepository;
import com.chardizard.Norbiz.repositories.ItemSkuRepository;
import com.chardizard.Norbiz.repositories.UserRepository;
import com.chardizard.Norbiz.util.SpecificationUtils;
import com.chardizard.Norbiz.util.ForeignKeyViolations;
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
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ItemSkuService {

    private static final Logger log = LoggerFactory.getLogger(ItemSkuService.class);

    private final ItemSkuRepository itemSkuRepository;
    private final ItemRepository itemRepository;
    private final CompanyRepository companyRepository;
    private final ItemCategoryRepository itemCategoryRepository;
    private final BrandRepository brandRepository;
    private final UserRepository userRepository;

    public Page<ItemSku> findAll(String username, Map<String, String> filters, Pageable pageable) {
        User user = getUser(username);

        Specification<ItemSku> companyScope = null;
        if (!isSuperAdmin(user)) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .toList();
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<ItemSku> spec = SpecificationUtils.allOf(
                companyScope,
                SpecificationUtils.containsIgnoreCase("skuCode", filters.get("skuCode")),
                SpecificationUtils.containsIgnoreCase("item.itemCode", filters.get("itemCode")),
                SpecificationUtils.containsIgnoreCase("item.name", filters.get("itemName")),
                SpecificationUtils.containsIgnoreCase("unitPrice", filters.get("unitPrice")),
                idEquals("brand", filters.get("brandId")),
                idEquals("itemCategory", filters.get("itemCategoryId")),
                priceEquals(filters.get("price")),
                SpecificationUtils.containsIgnoreCase("itemCategory.name", filters.get("itemCategory")),
                SpecificationUtils.containsIgnoreCase("brand.name", filters.get("brand")),
                SpecificationUtils.enumEquals("priceType", PriceType.class, filters.get("priceType")),
                SpecificationUtils.containsIgnoreCase("barcode", filters.get("barcode")),
                SpecificationUtils.containsIgnoreCase("storeItemCode", filters.get("storeItemCode")),
                SpecificationUtils.booleanEquals("active", filters.get("active")),
                assigned(filters.get("assigned"))
        );

        return itemSkuRepository.findAll(spec, pageable);
    }

    public ItemSku findById(Long id, String username) {
        ItemSku sku = itemSkuRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("SKU not found: " + id));
        assertCompanyAccess(username, sku.getCompany().getId());
        return sku;
    }

    @Transactional
    public ItemSku create(ItemSkuRequest request, String username) {
        if (request.getSkuCode() == null || request.getSkuCode().isBlank()) {
            throw new IllegalArgumentException("skuCode is required");
        }
        if (request.getUnitPrice() == null) {
            throw new IllegalArgumentException("unitPrice is required");
        }

        Item item = null;
        Company company;
        if (request.getItemId() != null) {
            item = itemRepository.findById(request.getItemId())
                    .orElseThrow(() -> new IllegalArgumentException("Item not found: " + request.getItemId()));
            company = item.getCompany();
            if (request.getCompanyId() != null && !request.getCompanyId().equals(company.getId())) {
                throw new IllegalArgumentException("Item " + item.getItemCode() + " does not belong to company: " + request.getCompanyId());
            }
        } else {
            if (request.getCompanyId() == null) {
                throw new IllegalArgumentException("companyId is required when creating a SKU without an item");
            }
            company = companyRepository.findById(request.getCompanyId())
                    .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));
        }

        assertCompanyAccess(username, company.getId());

        // A code may be shared by several items, but each item lists it once.
        if (item != null && itemSkuRepository.existsByItemIdAndSkuCode(item.getId(), request.getSkuCode())) {
            throw new IllegalArgumentException("Item " + item.getItemCode() + " already has SKU code " + request.getSkuCode());
        }

        ItemSku sku = new ItemSku();
        sku.setCompany(company);
        sku.setItem(item);
        sku.setSkuCode(request.getSkuCode());
        sku.setUnitPrice(request.getUnitPrice());
        applyDetails(sku, request);

        ItemSku saved = itemSkuRepository.save(sku);
        log.info("User '{}' created SKU '{}' (id={}) for company {}, item {}", username, saved.getSkuCode(), saved.getId(),
                company.getId(), item != null ? item.getId() : null);
        return saved;
    }

    @Transactional
    public ItemSku update(Long id, ItemSkuRequest request, String username) {
        if (request.getSkuCode() == null || request.getSkuCode().isBlank()) {
            throw new IllegalArgumentException("skuCode is required");
        }
        if (request.getUnitPrice() == null) {
            throw new IllegalArgumentException("unitPrice is required");
        }

        ItemSku sku = findById(id, username);

        if (sku.getItem() != null && !sku.getSkuCode().equals(request.getSkuCode())
                && itemSkuRepository.existsByItemIdAndSkuCode(sku.getItem().getId(), request.getSkuCode())) {
            throw new IllegalArgumentException("Item " + sku.getItem().getItemCode() + " already has SKU code " + request.getSkuCode());
        }

        sku.setSkuCode(request.getSkuCode());
        sku.setUnitPrice(request.getUnitPrice());
        applyDetails(sku, request);

        ItemSku saved = itemSkuRepository.save(sku);
        log.info("User '{}' updated SKU '{}' (id={})", username, saved.getSkuCode(), saved.getId());
        return saved;
    }

    @Transactional
    public void delete(Long id, String username) {
        ItemSku sku = findById(id, username);
        ForeignKeyViolations.deleteOrThrow(itemSkuRepository, sku, "SKU", id);
        log.info("User '{}' deleted SKU '{}' (id={})", username, sku.getSkuCode(), id);
    }

    /** Exact match on an association's id (e.g. brand), so the brand/category/price index is usable. */
    private static Specification<ItemSku> idEquals(String association, String id) {
        if (!StringUtils.hasText(id)) return null;
        long value = Long.parseLong(id);
        return (root, query, cb) -> cb.equal(root.get(association).get("id"), value);
    }

    private static Specification<ItemSku> priceEquals(String price) {
        if (!StringUtils.hasText(price)) return null;
        BigDecimal value = new BigDecimal(price);
        return (root, query, cb) -> cb.equal(root.get("unitPrice"), value);
    }

    /** "true" keeps SKUs assigned to an item, "false" those without one; blank is a no-op. */
    private static Specification<ItemSku> assigned(String value) {
        if (!StringUtils.hasText(value)) return null;
        boolean wanted = Boolean.parseBoolean(value);
        return (root, query, cb) -> wanted ? cb.isNotNull(root.get("item")) : cb.isNull(root.get("item"));
    }

    // The optional fields: null leaves the current value as-is (so a client that only edits code and
    // price can't wipe them), a blank string clears a text field.
    private void applyDetails(ItemSku sku, ItemSkuRequest request) {
        Long companyId = sku.getCompany().getId();
        if (request.getItemCategoryId() != null) {
            ItemCategory category = itemCategoryRepository.findById(request.getItemCategoryId())
                    .orElseThrow(() -> new IllegalArgumentException("Item category not found: " + request.getItemCategoryId()));
            if (!category.getCompany().getId().equals(companyId)) {
                throw new IllegalArgumentException("Item category does not belong to company: " + companyId);
            }
            sku.setItemCategory(category);
        }
        if (request.getBrandId() != null) {
            Brand brand = brandRepository.findById(request.getBrandId())
                    .orElseThrow(() -> new IllegalArgumentException("Brand not found: " + request.getBrandId()));
            if (!brand.getCompany().getId().equals(companyId)) {
                throw new IllegalArgumentException("Brand does not belong to company: " + companyId);
            }
            sku.setBrand(brand);
        }
        if (request.getPriceType() != null) {
            if (request.getPriceType() != PriceType.UNIT_PRICE && request.getPriceType() != PriceType.FOCAL_PRICE) {
                throw new IllegalArgumentException("priceType must be UNIT_PRICE or FOCAL_PRICE");
            }
            sku.setPriceType(request.getPriceType());
        }
        if (request.getStoreItemCode() != null) sku.setStoreItemCode(blankToNull(request.getStoreItemCode()));
        if (request.getBarcode() != null) sku.setBarcode(blankToNull(request.getBarcode()));
        if (request.getVendorPart() != null) sku.setVendorPart(blankToNull(request.getVendorPart()));
        if (request.getRdsDescription() != null) sku.setRdsDescription(blankToNull(request.getRdsDescription()));
        if (request.getActive() != null) sku.setActive(request.getActive());
        if (request.getRdsSku() != null) sku.setRdsSku(request.getRdsSku());
        if (request.getLandmarkSku() != null) sku.setLandmarkSku(request.getLandmarkSku());
    }

    private static String blankToNull(String value) {
        return value.isBlank() ? null : value.trim();
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
