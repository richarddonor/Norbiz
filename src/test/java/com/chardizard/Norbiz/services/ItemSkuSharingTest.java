package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.ItemSkuRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private String user;
    private Item ring;
    private Item necklace;

    @BeforeEach
    void setUp() {
        Company company = new Company();
        company.setName("SKU Co " + suffix);
        company = companyRepository.save(company);

        User u = new User();
        u.setUsername("sku_user_" + suffix);
        u.setEmail(u.getUsername() + "@test.local");
        u.setPassword("x");
        u.setCompanies(Set.of(company));
        user = userRepository.save(u).getUsername();

        ItemCategory category = new ItemCategory();
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
        req.setItemId(item.getId());
        req.setSkuCode(code);
        req.setUnitPrice(new BigDecimal("10.00"));
        return req;
    }
}
