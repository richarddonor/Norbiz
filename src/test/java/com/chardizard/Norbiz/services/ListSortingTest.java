package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import com.chardizard.Norbiz.util.ListSort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Column sorting (ListSort) combined with "contains" filters. Runs against the configured Postgres; every test rolls back.
@SpringBootTest
@Transactional
class ListSortingTest {

    private static final Map<String, String> ITEM_SORTABLE = ListSort.fields(
            "company", "company.name", "name", "name", "group", "itemGroup.name");

    @Autowired ItemService itemService;
    @Autowired CompanyRepository companyRepository;
    @Autowired UserRepository userRepository;
    @Autowired ItemCategoryRepository itemCategoryRepository;
    @Autowired ItemGroupRepository itemGroupRepository;
    @Autowired ItemRepository itemRepository;
    @Autowired ItemSkuRepository itemSkuRepository;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private String user;
    private Item grouped;
    private Item ungrouped;

    @BeforeEach
    void setUp() {
        Company company = new Company();
        company.setName("Sort Co " + suffix);
        company = companyRepository.save(company);

        User u = new User();
        u.setUsername("sort_user_" + suffix);
        u.setEmail(u.getUsername() + "@test.local");
        u.setPassword("x");
        u.setCompanies(Set.of(company));
        user = userRepository.save(u).getUsername();

        ItemCategory category = new ItemCategory();
        category.setCompany(company);
        category.setName("Sort Category " + suffix);
        category = itemCategoryRepository.save(category);

        ItemGroup group = new ItemGroup();
        group.setCompany(company);
        group.setName("Sort Group " + suffix);
        group = itemGroupRepository.save(group);

        grouped = item(company, category, group, "SRT-A-" + suffix, "Alpha");
        ungrouped = item(company, category, null, "SRT-B-" + suffix, "Bravo");
        // Two matching SKUs on one item: a to-many filter must still return it once.
        sku(company, grouped, "SKU1-" + suffix);
        sku(company, grouped, "SKU2-" + suffix);
        sku(company, ungrouped, "SKU3-" + suffix);
    }

    @Test
    void toManyFilterWithJoinedColumnSortNeitherFailsNorDuplicatesNorDropsNullAssociations() {
        // Before EXISTS filters this was SELECT DISTINCT … ORDER BY item_group.name, which Postgres rejects.
        var page = itemService.findAllForUser(user, Map.of("skus", "sku", "company", "sort co"), List.of(),
                ListSort.apply(PageRequest.of(0, 50, Sort.by(Sort.Order.asc("group"))), ITEM_SORTABLE, ListSort.UPDATED_FIRST));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Item::getId).containsExactlyInAnyOrder(grouped.getId(), ungrouped.getId());
    }

    @Test
    void sortsByTheRequestedColumnInEitherDirection() {
        var asc = itemService.findAllForUser(user, Map.of(), List.of(),
                ListSort.apply(PageRequest.of(0, 50, Sort.by(Sort.Order.asc("name"))), ITEM_SORTABLE, ListSort.UPDATED_FIRST));
        var desc = itemService.findAllForUser(user, Map.of(), List.of(),
                ListSort.apply(PageRequest.of(0, 50, Sort.by(Sort.Order.desc("name"))), ITEM_SORTABLE, ListSort.UPDATED_FIRST));

        assertThat(asc.getContent()).extracting(Item::getName).containsExactly("Alpha", "Bravo");
        assertThat(desc.getContent()).extracting(Item::getName).containsExactly("Bravo", "Alpha");
    }

    private Item item(Company company, ItemCategory category, ItemGroup group, String code, String name) {
        Item item = new Item();
        item.setCompany(company);
        item.setItemCategory(category);
        item.setItemGroup(group);
        item.setItemCode(code);
        item.setName(name);
        return itemRepository.save(item);
    }

    private void sku(Company company, Item item, String code) {
        ItemSku sku = new ItemSku();
        sku.setCompany(company);
        sku.setItem(item);
        sku.setSkuCode(code);
        sku.setUnitPrice(new BigDecimal("10"));
        itemSkuRepository.save(sku);
        item.getSkus().add(sku);
    }
}
