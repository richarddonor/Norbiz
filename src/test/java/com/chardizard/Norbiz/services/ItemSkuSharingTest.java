package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.ItemSkuRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// SKU codes are unique per item only: several items may share one (legacy department-store SKUs
// cover many items), but an item can't list the same code twice. Runs against the configured
// Postgres; every test rolls back.
@SpringBootTest
@Transactional
class ItemSkuSharingTest {

    @Autowired ItemSkuService itemSkuService;
    @Autowired CompanyRepository companyRepository;
    @Autowired ItemCategoryRepository itemCategoryRepository;
    @Autowired ItemRepository itemRepository;
    @Autowired UserRepository userRepository;
    @Autowired BrandRepository brandRepository;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private String user;
    private Company company;
    private ItemCategory category;
    private Item ring;
    private Item necklace;

    @BeforeEach
    void setUp() {
        company = new Company();
        company.setName("SKU Co " + suffix);
        company = companyRepository.save(company);

        User u = new User();
        u.setUsername("sku_user_" + suffix);
        u.setEmail(u.getUsername() + "@test.local");
        u.setPassword("x");
        u.setCompanies(Set.of(company));
        user = userRepository.save(u).getUsername();

        category = new ItemCategory();
        category.setCompany(company);
        category.setName("SKU Category " + suffix);
        category = itemCategoryRepository.save(category);

        ring = item(company, category, "RING-" + suffix);
        necklace = item(company, category, "NECK-" + suffix);
    }

    @Test
    void severalItemsCanShareASkuCode() {
        String code = "SM-" + suffix;
        ItemSku first = itemSkuService.create(request(ring, code), user);
        ItemSku second = itemSkuService.create(request(necklace, code), user);

        assertThat(first.getSkuCode()).isEqualTo(second.getSkuCode());
        assertThat(first.getItem().getId()).isNotEqualTo(second.getItem().getId());
    }

    @Test
    void anItemCantListTheSameCodeTwice() {
        String code = "SM-" + suffix;
        itemSkuService.create(request(ring, code), user);

        assertThatThrownBy(() -> itemSkuService.create(request(ring, code), user))
                .hasMessageContaining("already has SKU code");
    }

    @Test
    void renamingToACodeTheItemAlreadyHasIsRejected() {
        itemSkuService.create(request(ring, "A-" + suffix), user);
        ItemSku other = itemSkuService.create(request(ring, "B-" + suffix), user);

        assertThatThrownBy(() -> itemSkuService.update(other.getId(), request(ring, "A-" + suffix), user))
                .hasMessageContaining("already has SKU code");
    }

    @Test
    void aSkuCanExistWithoutAnItem() {
        ItemSkuRequest req = request(null, "LOOSE-" + suffix);
        req.setCompanyId(company.getId());
        req.setBarcode("4800000000017");
        req.setActive(false);

        ItemSku sku = itemSkuService.create(req, user);

        assertThat(sku.getItem()).isNull();
        assertThat(sku.getCompany().getId()).isEqualTo(company.getId());
        assertThat(sku.isActive()).isFalse();
        assertThat(itemSkuService.findById(sku.getId(), user).getBarcode()).isEqualTo("4800000000017");
    }

    @Test
    void anItemLessSkuNeedsACompany() {
        assertThatThrownBy(() -> itemSkuService.create(request(null, "LOOSE-" + suffix), user))
                .hasMessageContaining("companyId is required");
    }

    @Test
    void aSkuTakesItsItemsCompany() {
        ItemSku sku = itemSkuService.create(request(ring, "SM-" + suffix), user);

        assertThat(sku.getCompany().getId()).isEqualTo(company.getId());
    }

    @Test
    void updatingOnlyCodeAndPriceKeepsTheOtherFields() {
        ItemSkuRequest req = request(ring, "PP-" + suffix);
        req.setBarcode("4800000000017");
        req.setPriceType(PriceType.FOCAL_PRICE);
        req.setRdsSku(true);
        ItemSku sku = itemSkuService.create(req, user);

        ItemSku updated = itemSkuService.update(sku.getId(), request(ring, "PP-" + suffix), user);

        assertThat(updated.getBarcode()).isEqualTo("4800000000017");
        assertThat(updated.getPriceType()).isEqualTo(PriceType.FOCAL_PRICE);
        assertThat(updated.isRdsSku()).isTrue();
    }

    @Test
    void costPriceIsNotASkuPriceType() {
        ItemSkuRequest req = request(ring, "PP-" + suffix);
        req.setPriceType(PriceType.COST_PRICE);

        assertThatThrownBy(() -> itemSkuService.create(req, user))
                .hasMessageContaining("priceType must be UNIT_PRICE or FOCAL_PRICE");
    }

    @Test
    void skusAreFoundByBrandThenCategoryThenPrice() {
        Brand sm = new Brand();
        sm.setCompany(company);
        sm.setName("SM " + suffix);
        sm = brandRepository.save(sm);

        ItemSkuRequest match = request(ring, "SM-" + suffix);
        match.setBrandId(sm.getId());
        match.setItemCategoryId(category.getId());
        match.setUnitPrice(new BigDecimal("749.00"));
        ItemSku wanted = itemSkuService.create(match, user);

        ItemSkuRequest otherPrice = request(necklace, "SM2-" + suffix);
        otherPrice.setBrandId(sm.getId());
        otherPrice.setItemCategoryId(category.getId());
        otherPrice.setUnitPrice(new BigDecimal("999.75"));
        itemSkuService.create(otherPrice, user);

        var page = itemSkuService.findAll(user, Map.of("brandId", sm.getId().toString(),
                "itemCategoryId", category.getId().toString(), "price", "749"), PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(ItemSku::getId).containsExactly(wanted.getId());
    }

    private Item item(Company company, ItemCategory category, String code) {
        Item i = new Item();
        i.setCompany(company);
        i.setItemCategory(category);
        i.setItemCode(code);
        i.setName("Item " + code);
        return itemRepository.save(i);
    }

    private ItemSkuRequest request(Item item, String code) {
        ItemSkuRequest req = new ItemSkuRequest();
        req.setItemId(item != null ? item.getId() : null);
        req.setSkuCode(code);
        req.setUnitPrice(new BigDecimal("10.00"));
        return req;
    }
}
