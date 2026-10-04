package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// Delivery Receipt + Outlet Receive ledger effects. Runs against the configured Postgres; every test rolls back.
// TransactionReferenceService is mocked for the same reason as in TransactionEventServiceTest.
@SpringBootTest
@Transactional
class DeliveryReceiptFlowTest {

    @Autowired DeliveryReceiptService deliveryReceiptService;
    @Autowired OutletReceiveService outletReceiveService;
    @Autowired CustomerService customerService;
    @Autowired CompanyRepository companyRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired ItemCategoryRepository itemCategoryRepository;
    @Autowired ItemRepository itemRepository;
    @Autowired ItemPriceRepository itemPriceRepository;
    @Autowired InventoryBalanceRepository inventoryBalanceRepository;
    @Autowired UserRepository userRepository;
    @MockitoBean TransactionReferenceService transactionReferenceService;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private int referenceCounter = 0;

    private String user;
    private Company company;
    private Warehouse mainWarehouse;
    private Customer customer;
    private Customer outlet;
    private Item item;

    @BeforeEach
    void setUp() {
        when(transactionReferenceService.next(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2) + "-TEST-" + suffix + "-" + (++referenceCounter));

        company = new Company();
        company.setName("DR Co " + suffix);
        company = companyRepository.save(company);

        User u = new User();
        u.setUsername("dr_user_" + suffix);
        u.setEmail(u.getUsername() + "@test.local");
        u.setPassword("x");
        u.setCompanies(Set.of(company));
        user = userRepository.save(u).getUsername();

        mainWarehouse = new Warehouse();
        mainWarehouse.setCompany(company);
        mainWarehouse.setCode("MAIN" + suffix);
        mainWarehouse.setName("Main");
        mainWarehouse.setMain(true);
        mainWarehouse = warehouseRepository.save(mainWarehouse);

        customer = customerService.create(customerRequest("CUS" + suffix, CustomerType.CUSTOMER), user);
        outlet = customerService.create(customerRequest("OUT" + suffix, CustomerType.OUTLET), user);

        ItemCategory category = new ItemCategory();
        category.setCompany(company);
        category.setName("DR Category " + suffix);
        category = itemCategoryRepository.save(category);

        item = new Item();
        item.setCompany(company);
        item.setItemCategory(category);
        item.setItemCode("DR-" + suffix);
        item.setName("DR Item");
        item.getTags().add(ItemTag.INVENTORY);
        item = itemRepository.save(item);

        ItemPrice price = new ItemPrice();
        price.setItem(item);
        price.setPriceType(PriceType.UNIT_PRICE);
        price.setAmount(new BigDecimal("25.00"));
        itemPriceRepository.save(price);
    }

    @Test
    void plainCustomerDeliveryOnlyDeductsMainStock() {
        DeliveryReceipt dr = deliveryReceiptService.create(drRequest(customer, "10"), user);

        assertThat(dr.getWarehouse().getId()).isEqualTo(mainWarehouse.getId());
        assertThat(dr.getDestinationWarehouse()).isNull();
        assertBalance(mainWarehouse, "-10", "0");
    }

    @Test
    void unitPriceDefaultsToItemUnitPriceButCanBeOverridden() {
        DeliveryReceipt preloaded = deliveryReceiptService.create(drRequest(customer, "1"), user);
        assertThat(preloaded.getLines().getFirst().getUnitPrice()).isEqualByComparingTo("25.00");

        DeliveryReceiptRequest req = drRequest(customer, "1");
        req.getLines().getFirst().setUnitPrice(new BigDecimal("19.50"));
        DeliveryReceipt overridden = deliveryReceiptService.create(req, user);
        assertThat(overridden.getLines().getFirst().getUnitPrice()).isEqualByComparingTo("19.50");
    }

    @Test
    void outletDeliveryAlsoPostsTransitInOutletWarehouseAndVoidReversesBoth() {
        DeliveryReceipt dr = deliveryReceiptService.create(drRequest(outlet, "10"), user);

        assertThat(dr.getDestinationWarehouse().getId()).isEqualTo(outlet.getWarehouse().getId());
        assertBalance(mainWarehouse, "-10", "0");
        assertBalance(outlet.getWarehouse(), "0", "10");

        deliveryReceiptService.voidDeliveryReceipt(dr.getId(), user);
        assertBalance(mainWarehouse, "0", "0");
        assertBalance(outlet.getWarehouse(), "0", "0");
    }

    @Test
    void deliveryWithoutMainWarehouseIsRejected() {
        mainWarehouse.setMain(false);
        warehouseRepository.saveAndFlush(mainWarehouse);
        assertThatThrownBy(() -> deliveryReceiptService.create(drRequest(customer, "1"), user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No main warehouse");
    }

    @Test
    void inactiveCustomerIsRejected() {
        customer.setActive(false);
        assertThatThrownBy(() -> deliveryReceiptService.create(drRequest(customer, "1"), user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Customer is inactive");
    }

    @Test
    void partialThenFullOutletReceiveLoadsTheDeliveryReceipt() {
        DeliveryReceipt dr = deliveryReceiptService.create(drRequest(outlet, "10"), user);

        outletReceiveService.create(orRequest(dr, "4"), user);
        assertThat(dr.isLoaded()).isFalse();
        assertThat(dr.getLines().getFirst().getQuantityLoaded()).isEqualByComparingTo("4");
        assertBalance(outlet.getWarehouse(), "4", "6");

        outletReceiveService.create(orRequest(dr, "6"), user);
        assertThat(dr.isLoaded()).isTrue();
        assertBalance(outlet.getWarehouse(), "10", "0");

        assertThatThrownBy(() -> outletReceiveService.create(orRequest(dr, "1"), user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already fully received");
    }

    @Test
    void overReceiveIsRejected() {
        DeliveryReceipt dr = deliveryReceiptService.create(drRequest(outlet, "5"), user);
        assertThatThrownBy(() -> outletReceiveService.create(orRequest(dr, "6"), user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exceeds outstanding");
    }

    @Test
    void nonOutletDeliveryCannotBeReceived() {
        DeliveryReceipt dr = deliveryReceiptService.create(drRequest(customer, "5"), user);
        assertThatThrownBy(() -> outletReceiveService.create(orRequest(dr, "1"), user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-outlet");
    }

    @Test
    void receivedDeliveryCannotBeVoidedUntilTheReceiveIsVoided() {
        DeliveryReceipt dr = deliveryReceiptService.create(drRequest(outlet, "10"), user);
        OutletReceive or = outletReceiveService.create(orRequest(dr, "10"), user);

        assertThatThrownBy(() -> deliveryReceiptService.voidDeliveryReceipt(dr.getId(), user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("received by an outlet");

        outletReceiveService.voidOutletReceive(or.getId(), user);
        assertThat(dr.isLoaded()).isFalse();
        assertThat(dr.getLines().getFirst().getQuantityLoaded()).isEqualByComparingTo("0");
        assertBalance(outlet.getWarehouse(), "0", "10");

        deliveryReceiptService.voidDeliveryReceipt(dr.getId(), user);
        assertBalance(mainWarehouse, "0", "0");
        assertBalance(outlet.getWarehouse(), "0", "0");
    }

    private void assertBalance(Warehouse warehouse, String quantity, String transitQuantity) {
        InventoryBalance balance = inventoryBalanceRepository.findByItemIdAndWarehouseId(item.getId(), warehouse.getId()).orElseThrow();
        assertThat(balance.getQuantity()).as("quantity in " + warehouse.getName()).isEqualByComparingTo(quantity);
        assertThat(balance.getTransitQuantity()).as("transit in " + warehouse.getName()).isEqualByComparingTo(transitQuantity);
    }

    private CustomerRequest customerRequest(String code, CustomerType type) {
        CustomerRequest req = new CustomerRequest();
        req.setCompanyId(company.getId());
        req.setCode(code);
        req.setName("Name " + code);
        req.setType(type);
        return req;
    }

    private DeliveryReceiptRequest drRequest(Customer to, String quantity) {
        DeliveryReceiptLineRequest line = new DeliveryReceiptLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(new BigDecimal(quantity));
        DeliveryReceiptRequest req = new DeliveryReceiptRequest();
        req.setCompanyId(company.getId());
        req.setCustomerId(to.getId());
        req.setDeliveryDate("2026-10-04");
        req.setLines(new java.util.ArrayList<>(List.of(line)));
        return req;
    }

    private OutletReceiveRequest orRequest(DeliveryReceipt dr, String quantity) {
        OutletReceiveLineRequest line = new OutletReceiveLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(new BigDecimal(quantity));
        OutletReceiveRequest req = new OutletReceiveRequest();
        req.setCompanyId(company.getId());
        req.setDeliveryReceiptId(dr.getId());
        req.setReceiptDate("2026-10-05");
        req.setLines(List.of(line));
        return req;
    }
}
