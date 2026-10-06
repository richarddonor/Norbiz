package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// Outlet Delivery Receipt + Outlet Delivery Return ledger effects. Runs against the configured Postgres; every test rolls back.
// TransactionReferenceService is mocked for the same reason as in TransactionEventServiceTest.
@SpringBootTest
@Transactional
class OutletDeliveryReceiptFlowTest {

    @Autowired OutletDeliveryReceiptService outletDeliveryReceiptService;
    @Autowired OutletDeliveryReturnService outletDeliveryReturnService;
    @Autowired TransactionDetailedReportService reportService;
    @Autowired CustomerService customerService;
    @Autowired CompanyRepository companyRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired ItemCategoryRepository itemCategoryRepository;
    @Autowired ItemRepository itemRepository;
    @Autowired ItemPriceRepository itemPriceRepository;
    @Autowired InventoryBalanceRepository inventoryBalanceRepository;
    @Autowired UserRepository userRepository;
    @Autowired InventoryAdjustmentService inventoryAdjustmentService;
    @MockitoBean TransactionReferenceService transactionReferenceService;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private int referenceCounter = 0;

    private String user;
    private Company company;
    private Customer customer;
    private Customer outlet;
    private Employee agent;
    private Employee nonAgent;
    private Item item;

    @BeforeEach
    void setUp() {
        when(transactionReferenceService.next(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2) + "-TEST-" + suffix + "-" + (++referenceCounter));

        company = new Company();
        company.setName("ODR Co " + suffix);
        company = companyRepository.save(company);

        User u = new User();
        u.setUsername("odr_user_" + suffix);
        u.setEmail(u.getUsername() + "@test.local");
        u.setPassword("x");
        u.setCompanies(Set.of(company));
        user = userRepository.save(u).getUsername();

        customer = customerService.create(customerRequest("CUS" + suffix, CustomerType.CUSTOMER), user);
        outlet = customerService.create(customerRequest("OUT" + suffix, CustomerType.OUTLET), user);

        agent = employee("AG" + suffix, Set.of(EmployeeTag.AGENT));
        nonAgent = employee("EM" + suffix, Set.of());

        ItemCategory category = new ItemCategory();
        category.setCompany(company);
        category.setName("ODR Category " + suffix);
        category = itemCategoryRepository.save(category);

        item = new Item();
        item.setCompany(company);
        item.setItemCategory(category);
        item.setItemCode("ODR-" + suffix);
        item.setName("ODR Item");
        item.getTags().add(ItemTag.INVENTORY);
        item = itemRepository.save(item);

        ItemPrice price = new ItemPrice();
        price.setItem(item);
        price.setPriceType(PriceType.UNIT_PRICE);
        price.setAmount(new BigDecimal("25.00"));
        itemPriceRepository.save(price);

        stock(outlet.getWarehouse(), "100");
    }

    @Test
    void saleDeductsOutletOnHandAndVoidRestoresIt() {
        OutletDeliveryReceipt odr = outletDeliveryReceiptService.create(odrRequest(outlet, agent, "10"), user);

        assertThat(odr.getWarehouse().getId()).isEqualTo(outlet.getWarehouse().getId());
        assertThat(odr.getAgent().getId()).isEqualTo(agent.getId());
        assertThat(odr.getLines().getFirst().getUnitPrice()).isEqualByComparingTo("25.00");
        assertBalance(outlet.getWarehouse(), "90", "0");

        outletDeliveryReceiptService.voidOutletDeliveryReceipt(odr.getId(), user);
        assertBalance(outlet.getWarehouse(), "100", "0");
    }

    @Test
    void plainCustomerIsRejected() {
        assertThatThrownBy(() -> outletDeliveryReceiptService.create(odrRequest(customer, agent, "1"), user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not an outlet");
    }

    @Test
    void employeeWithoutAgentTagIsRejected() {
        assertThatThrownBy(() -> outletDeliveryReceiptService.create(odrRequest(outlet, nonAgent, "1"), user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not tagged as an agent");
    }

    @Test
    void inactiveAgentIsRejected() {
        agent.setActive(false);
        assertThatThrownBy(() -> outletDeliveryReceiptService.create(odrRequest(outlet, agent, "1"), user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Agent is inactive");
    }

    @Test
    void partialThenFullReturnLoadsTheReceiptAndCopiesAgentAndPrice() {
        OutletDeliveryReceiptRequest req = odrRequest(outlet, agent, "10");
        req.getLines().getFirst().setUnitPrice(new BigDecimal("19.50"));
        OutletDeliveryReceipt odr = outletDeliveryReceiptService.create(req, user);

        OutletDeliveryReturn ret = outletDeliveryReturnService.create(returnRequest(odr, "4"), user);
        assertThat(ret.getAgent().getId()).isEqualTo(agent.getId());
        assertThat(ret.getWarehouse().getId()).isEqualTo(outlet.getWarehouse().getId());
        assertThat(ret.getLines().getFirst().getUnitPrice()).isEqualByComparingTo("19.50");
        assertThat(odr.isLoaded()).isFalse();
        assertThat(odr.getLines().getFirst().getQuantityLoaded()).isEqualByComparingTo("4");
        assertBalance(outlet.getWarehouse(), "94", "0");

        outletDeliveryReturnService.create(returnRequest(odr, "6"), user);
        assertThat(odr.isLoaded()).isTrue();
        assertBalance(outlet.getWarehouse(), "100", "0");

        assertThatThrownBy(() -> outletDeliveryReturnService.create(returnRequest(odr, "1"), user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already fully returned");
    }

    @Test
    void overReturnIsRejected() {
        OutletDeliveryReceipt odr = outletDeliveryReceiptService.create(odrRequest(outlet, agent, "5"), user);
        assertThatThrownBy(() -> outletDeliveryReturnService.create(returnRequest(odr, "6"), user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exceeds outstanding");
    }

    @Test
    void returnedReceiptCannotBeVoidedUntilTheReturnIsVoided() {
        OutletDeliveryReceipt odr = outletDeliveryReceiptService.create(odrRequest(outlet, agent, "10"), user);
        OutletDeliveryReturn ret = outletDeliveryReturnService.create(returnRequest(odr, "3"), user);

        assertThatThrownBy(() -> outletDeliveryReceiptService.voidOutletDeliveryReceipt(odr.getId(), user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has returns");

        outletDeliveryReturnService.voidOutletDeliveryReturn(ret.getId(), user);
        assertThat(odr.getLines().getFirst().getQuantityLoaded()).isEqualByComparingTo("0");
        assertBalance(outlet.getWarehouse(), "90", "0");

        outletDeliveryReceiptService.voidOutletDeliveryReceipt(odr.getId(), user);
        assertBalance(outlet.getWarehouse(), "100", "0");
    }

    @Test
    void returnAgainstVoidedReceiptIsRejected() {
        OutletDeliveryReceipt odr = outletDeliveryReceiptService.create(odrRequest(outlet, agent, "2"), user);
        outletDeliveryReceiptService.voidOutletDeliveryReceipt(odr.getId(), user);
        assertThatThrownBy(() -> outletDeliveryReturnService.create(returnRequest(odr, "1"), user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("voided");
    }

    @Test
    void detailedReportRowsCarryTheAgentAndFilterByIt() {
        OutletDeliveryReceipt odr = outletDeliveryReceiptService.create(odrRequest(outlet, agent, "2"), user);
        outletDeliveryReturnService.create(returnRequest(odr, "1"), user);

        DetailedReportFilter filter = new DetailedReportFilter();
        filter.setAgentId(agent.getId());
        Page<TransactionDetailedReportRow> sales = reportService.find(DetailedReportType.OUTLET_DELIVERY_RECEIPT, user, filter, false, PageRequest.of(0, 50));
        assertThat(sales.getContent()).hasSize(1);
        TransactionDetailedReportRow row = sales.getContent().getFirst();
        assertThat(row.getAgentName()).isEqualTo(agent.getFirstName() + " " + agent.getLastName());
        assertThat(row.getCounterpartyId()).isEqualTo(outlet.getId());
        assertThat(row.getAmount()).isEqualByComparingTo("50.00");

        Page<TransactionDetailedReportRow> returns = reportService.find(DetailedReportType.OUTLET_DELIVERY_RETURN, user, filter, false, PageRequest.of(0, 50));
        assertThat(returns.getContent()).singleElement()
                .satisfies(r -> assertThat(r.getSourceReferenceNumber()).isEqualTo(odr.getReferenceNumber()));

        filter.setAgentId(nonAgent.getId());
        assertThat(reportService.find(DetailedReportType.OUTLET_DELIVERY_RECEIPT, user, filter, false, PageRequest.of(0, 50)).getContent()).isEmpty();
    }

    // Opening stock via a posted Inventory Adjustment — deliveries can't take on-hand below zero.
    private void stock(Warehouse warehouse, String quantity) {
        InventoryAdjustmentLineRequest line = new InventoryAdjustmentLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(new BigDecimal(quantity));
        InventoryAdjustmentRequest req = new InventoryAdjustmentRequest();
        req.setCompanyId(company.getId());
        req.setWarehouseId(warehouse.getId());
        req.setAdjustmentDate("2026-01-01");
        req.setLines(List.of(line));
        inventoryAdjustmentService.create(req, user);
    }

    private void assertBalance(Warehouse warehouse, String quantity, String transitQuantity) {
        InventoryBalance balance = inventoryBalanceRepository.findByItemIdAndWarehouseId(item.getId(), warehouse.getId()).orElseThrow();
        assertThat(balance.getQuantity()).as("quantity in " + warehouse.getName()).isEqualByComparingTo(quantity);
        assertThat(balance.getTransitQuantity()).as("transit in " + warehouse.getName()).isEqualByComparingTo(transitQuantity);
    }

    private Employee employee(String code, Set<EmployeeTag> tags) {
        Employee e = new Employee();
        e.setCompany(company);
        e.setEmployeeCode(code);
        e.setFirstName("First " + code);
        e.setLastName("Last " + code);
        e.getTags().addAll(tags);
        return employeeRepository.save(e);
    }

    private CustomerRequest customerRequest(String code, CustomerType type) {
        CustomerRequest req = new CustomerRequest();
        req.setCompanyId(company.getId());
        req.setCode(code);
        req.setName("Name " + code);
        req.setType(type);
        return req;
    }

    private OutletDeliveryReceiptRequest odrRequest(Customer from, Employee by, String quantity) {
        OutletDeliveryReceiptLineRequest line = new OutletDeliveryReceiptLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(new BigDecimal(quantity));
        OutletDeliveryReceiptRequest req = new OutletDeliveryReceiptRequest();
        req.setCompanyId(company.getId());
        req.setCustomerId(from.getId());
        req.setAgentId(by.getId());
        req.setDeliveryDate("2026-10-06");
        req.setLines(new ArrayList<>(List.of(line)));
        return req;
    }

    private OutletDeliveryReturnRequest returnRequest(OutletDeliveryReceipt odr, String quantity) {
        OutletDeliveryReturnLineRequest line = new OutletDeliveryReturnLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(new BigDecimal(quantity));
        OutletDeliveryReturnRequest req = new OutletDeliveryReturnRequest();
        req.setCompanyId(company.getId());
        req.setOutletDeliveryReceiptId(odr.getId());
        req.setReturnDate("2026-10-07");
        req.setLines(List.of(line));
        return req;
    }
}
