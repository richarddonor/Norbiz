package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.controllers.LookupController;
import com.chardizard.Norbiz.dto.ItemLookupResponse;
import com.chardizard.Norbiz.dto.LookupResponse;
import com.chardizard.Norbiz.dto.StockLookupResponse;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User.UserBuilder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Runs against the configured Postgres (same as NorbizApplicationTests); every test rolls back.
@SpringBootTest
@Transactional
class LookupServiceTest {

    @Autowired LookupService lookupService;
    @Autowired LookupController lookupController;
    @Autowired CompanyRepository companyRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired UserRepository userRepository;
    @Autowired ItemCategoryRepository itemCategoryRepository;
    @Autowired ItemRepository itemRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired InventoryBalanceRepository inventoryBalanceRepository;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private Company company;
    private Company otherCompany;
    private String buyer;      // same company, no roles — authorities are supplied per test
    private Supplier active;
    private Supplier inactive;
    private Supplier foreign;

    @BeforeEach
    void setUp() {
        company = company("LK Co " + suffix);
        otherCompany = company("LK Other " + suffix);
        buyer = user("lk_buyer_" + suffix, company);
        user("lk_out_" + suffix, otherCompany);

        active = supplier(company, "LKA" + suffix, "Acme " + suffix, true);
        inactive = supplier(company, "LKI" + suffix, "Dormant " + suffix, false);
        foreign = supplier(otherCompany, "LKF" + suffix, "Foreign " + suffix, true);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listIsScopedToOneCompanyAndActiveByDefault() {
        List<Long> ids = lookupService.suppliers(buyer, company.getId(), suffix, true, PageRequest.of(0, 50))
                .map(LookupResponse::getId).getContent();
        assertThat(ids).containsExactly(active.getId());

        List<Long> withInactive = lookupService.suppliers(buyer, company.getId(), suffix, false, PageRequest.of(0, 50))
                .map(LookupResponse::getId).getContent();
        assertThat(withInactive).containsExactlyInAnyOrder(active.getId(), inactive.getId());
    }

    @Test
    void companyIsRequired() {
        assertThatThrownBy(() -> lookupService.suppliers(buyer, null, null, true, PageRequest.of(0, 50)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("companyId is required");
    }

    @Test
    void foreignCompanyIsDeniedForListAndById() {
        assertThatThrownBy(() -> lookupService.suppliers(buyer, otherCompany.getId(), null, true, PageRequest.of(0, 50)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> lookupService.supplier(foreign.getId(), buyer))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void byIdResolvesInactiveSelection() {
        assertThat(lookupService.supplier(inactive.getId(), buyer).isActive()).isFalse();
    }

    @Test
    void userLookupOnlyReturnsMembersOfTheCompany() {
        List<String> codes = lookupService.users(buyer, company.getId(), "lk_", PageRequest.of(0, 50))
                .map(LookupResponse::getCode).getContent();
        assertThat(codes).contains(buyer).doesNotContain("lk_out_" + suffix);
    }

    @Test
    void itemLookupFiltersByTagAndGatesCostPrice() {
        ItemCategory category = new ItemCategory();
        category.setCompany(company);
        category.setName("LK Cat " + suffix);
        category = itemCategoryRepository.save(category);
        Item stocked = item(category, "LKS" + suffix, Set.of(ItemTag.INVENTORY), new BigDecimal("12.50"));
        item(category, "LKN" + suffix, Set.of(), BigDecimal.ONE);

        var hidden = lookupService.items(buyer, company.getId(), suffix, ItemTag.INVENTORY, true, false, PageRequest.of(0, 50));
        assertThat(hidden.getContent()).extracting(ItemLookupResponse::getId).containsExactly(stocked.getId());
        assertThat(hidden.getContent().getFirst().getCostPrice()).isNull();

        var shown = lookupService.items(buyer, company.getId(), suffix, ItemTag.INVENTORY, true, true, PageRequest.of(0, 50));
        assertThat(shown.getContent().getFirst().getCostPrice()).isEqualByComparingTo("12.50");
    }

    @Test
    void transactionCreatePermissionUnlocksSupplierLookupViaCompanyHeader() {
        UserDetails principal = authenticate(buyer, "CREATE_PURCHASE_ORDER");
        var response = lookupController.suppliers(principal, null, company.getId(), suffix, true, PageRequest.of(0, 50));
        assertThat(response.getBody().getData().getContent()).extracting(LookupResponse::getId).containsExactly(active.getId());
    }

    @Test
    void unrelatedPermissionIsDenied() {
        UserDetails principal = authenticate(buyer, "VIEW_INVENTORY_ADJUSTMENT");
        assertThatThrownBy(() -> lookupController.suppliers(principal, company.getId(), null, null, true, PageRequest.of(0, 50)))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void stockReturnsLiveBalancesAndZerosForUnmovedItems() {
        ItemCategory category = category();
        Item moved = item(category, "LKM" + suffix, Set.of(ItemTag.INVENTORY), BigDecimal.ONE);
        Item unmoved = item(category, "LKU" + suffix, Set.of(ItemTag.INVENTORY), BigDecimal.ONE);
        Warehouse warehouse = warehouse(company, "LK WH " + suffix);
        InventoryBalance balance = new InventoryBalance();
        balance.setItem(moved);
        balance.setWarehouse(warehouse);
        balance.setQuantity(new BigDecimal("7"));
        balance.setTransitQuantity(new BigDecimal("3"));
        balance.setUpdatedAt(Instant.now());
        inventoryBalanceRepository.save(balance);

        List<StockLookupResponse> rows = lookupService.stock(buyer, company.getId(), warehouse.getId(), List.of(moved.getId(), unmoved.getId()));

        assertThat(rows).extracting(StockLookupResponse::getItemId).containsExactlyInAnyOrder(moved.getId(), unmoved.getId());
        StockLookupResponse m = rows.stream().filter(r -> r.getItemId().equals(moved.getId())).findFirst().orElseThrow();
        assertThat(m.getQuantity()).isEqualByComparingTo("7");
        assertThat(m.getTransitQuantity()).isEqualByComparingTo("3");
        StockLookupResponse u = rows.stream().filter(r -> r.getItemId().equals(unmoved.getId())).findFirst().orElseThrow();
        assertThat(u.getQuantity()).isEqualByComparingTo("0");
        assertThat(u.getTransitQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void stockDeniesWarehouseOutsideRequestedOrAccessibleCompany() {
        Warehouse foreignWarehouse = warehouse(otherCompany, "LK Foreign WH " + suffix);
        // Own company requested, but the warehouse belongs to another one.
        assertThatThrownBy(() -> lookupService.stock(buyer, company.getId(), foreignWarehouse.getId(), List.of(1L)))
                .isInstanceOf(SecurityException.class);
        // The other company itself isn't accessible to the caller.
        assertThatThrownBy(() -> lookupService.stock(buyer, otherCompany.getId(), foreignWarehouse.getId(), List.of(1L)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void stockLookupOpenToTransactionCreatorsOnly() {
        Warehouse warehouse = warehouse(company, "LK WH2 " + suffix);
        UserDetails creator = authenticate(buyer, "CREATE_INVENTORY_ADJUSTMENT");
        var response = lookupController.stock(creator, null, company.getId(), warehouse.getId(), List.of(1L));
        assertThat(response.getBody().getData()).hasSize(1);

        UserDetails viewer = authenticate(buyer, "VIEW_SUPPLIER");
        assertThatThrownBy(() -> lookupController.stock(viewer, company.getId(), null, warehouse.getId(), List.of(1L)))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    private ItemCategory category() {
        ItemCategory category = new ItemCategory();
        category.setCompany(company);
        category.setName("LK Cat " + suffix);
        return itemCategoryRepository.save(category);
    }

    private Warehouse warehouse(Company company, String name) {
        Warehouse w = new Warehouse();
        w.setCompany(company);
        w.setName(name);
        return warehouseRepository.save(w);
    }

    private Item item(ItemCategory category, String code, Set<ItemTag> tags, BigDecimal cost) {
        Item i = new Item();
        i.setCompany(company);
        i.setItemCategory(category);
        i.setItemCode(code);
        i.setName("LK Item " + code);
        i.getTags().addAll(tags);
        ItemPrice price = new ItemPrice();
        price.setItem(i);
        price.setPriceType(PriceType.COST_PRICE);
        price.setAmount(cost);
        i.getPrices().add(price);
        return itemRepository.save(i);
    }

    private UserDetails authenticate(String username, String... authorities) {
        UserBuilder builder = org.springframework.security.core.userdetails.User.withUsername(username).password("x");
        UserDetails principal = builder.authorities(authorities).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
        return principal;
    }

    private Company company(String name) {
        Company c = new Company();
        c.setName(name);
        return companyRepository.save(c);
    }

    private Supplier supplier(Company company, String code, String name, boolean isActive) {
        Supplier s = new Supplier();
        s.setCompany(company);
        s.setCode(code);
        s.setName(name);
        s.setActive(isActive);
        return supplierRepository.save(s);
    }

    private String user(String username, Company company) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.local");
        u.setPassword("x");
        u.setCompanies(Set.of(company));
        userRepository.save(u);
        return username;
    }
}
