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
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// Pending Purchase Orders / Unpaid Purchase Invoices dashboard widgets. Runs against the configured Postgres;
// every test rolls back. TransactionReferenceService is mocked for the same reason as in TransactionEventServiceTest.
@SpringBootTest
@Transactional
class DashboardPurchasesTest {

    @Autowired DashboardService dashboardService;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired PurchaseInvoiceService purchaseInvoiceService;
    @Autowired CompanyRepository companyRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired ItemCategoryRepository itemCategoryRepository;
    @Autowired ItemRepository itemRepository;
    @Autowired UserRepository userRepository;
    @MockitoBean TransactionReferenceService transactionReferenceService;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private int referenceCounter = 0;

    private String user;
    private Company company;
    private Warehouse warehouse;
    private Supplier supplier;
    private Item item;

    @BeforeEach
    void setUp() {
        when(transactionReferenceService.next(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2) + "-TEST-" + suffix + "-" + (++referenceCounter));

        company = new Company();
        company.setName("Dash Co " + suffix);
        company = companyRepository.save(company);

        User u = new User();
        u.setUsername("dash_user_" + suffix);
        u.setEmail(u.getUsername() + "@test.local");
        u.setPassword("x");
        u.setCompanies(Set.of(company));
        user = userRepository.save(u).getUsername();

        warehouse = new Warehouse();
        warehouse.setCompany(company);
        warehouse.setCode("DW" + suffix);
        warehouse.setName("Dash Warehouse");
        warehouse = warehouseRepository.save(warehouse);

        supplier = new Supplier();
        supplier.setCompany(company);
        supplier.setCode("DS" + suffix);
        supplier.setName("Dash Supplier");
        supplier = supplierRepository.save(supplier);

        ItemCategory category = new ItemCategory();
        category.setCompany(company);
        category.setName("Dash Category " + suffix);
        category = itemCategoryRepository.save(category);

        item = new Item();
        item.setCompany(company);
        item.setItemCategory(category);
        item.setItemCode("DI-" + suffix);
        item.setName("Dash Item");
        item.getTags().add(ItemTag.INVENTORY);
        item = itemRepository.save(item);
    }

    @Test
    void pendingPurchaseOrdersHidesCostWithoutPermission() {
        purchaseOrderService.create(poRequest(), user, true);   // 10 × 100.00, ordered 2026-08-01

        BacklogResponse withCost = dashboardService.pendingPurchaseOrders(user, company.getId(), LocalDate.parse("2026-10-08"), true);
        assertThat(withCost.documentCount()).isEqualTo(1);
        assertThat(withCost.outstandingQuantity()).isEqualByComparingTo("10");
        assertThat(withCost.outstandingAmount()).isEqualByComparingTo("1000.00");
        assertThat(withCost.oldestAgeDays()).isEqualTo(68);
        assertThat(withCost.aging()).filteredOn(b -> b.documentCount() > 0).singleElement()
                .satisfies(b -> assertThat(b.label()).isEqualTo("61-90 days"));
        assertThat(withCost.breakdown()).singleElement().satisfies(b -> assertThat(b.name()).isEqualTo("Dash Warehouse"));

        BacklogResponse withoutCost = dashboardService.pendingPurchaseOrders(user, company.getId(), LocalDate.parse("2026-10-08"), false);
        assertThat(withoutCost.outstandingQuantity()).isEqualByComparingTo("10");
        assertThat(withoutCost.outstandingAmount()).isNull();
        assertThat(withoutCost.byCounterparty()).allSatisfy(s -> assertThat(s.amount()).isNull());
        assertThat(withoutCost.oldest()).allSatisfy(d -> assertThat(d.outstandingAmount()).isNull());
    }

    @Test
    void unpaidPurchaseInvoicesUseTheInvoiceNetPayable() {
        purchaseInvoiceService.create(piRequest(), user, true);

        BacklogResponse widget = dashboardService.unpaidPurchaseInvoices(user, company.getId(), LocalDate.parse("2026-10-08"), true);

        // 10 × 100.00 less 10% line discount = 900.00, less 10% header discount = 810.00, plus 50.00 fees = 860.00
        assertThat(widget.documentCount()).isEqualTo(1);
        assertThat(widget.outstandingAmount()).isEqualByComparingTo("860.00");
        assertThat(widget.outstandingQuantity()).isNull();
        assertThat(widget.progressPercent()).isNull();
        assertThat(widget.breakdown()).singleElement().satisfies(b -> assertThat(b.name()).isEqualTo("Unpaid"));
        assertThat(widget.byCounterparty()).singleElement().satisfies(s -> assertThat(s.id()).isEqualTo(supplier.getId()));

        BacklogResponse withoutCost = dashboardService.unpaidPurchaseInvoices(user, company.getId(), LocalDate.parse("2026-10-08"), false);
        assertThat(withoutCost.outstandingAmount()).isNull();
        assertThat(withoutCost.documentCount()).isEqualTo(1);
    }

    private PurchaseOrderRequest poRequest() {
        PurchaseOrderLineRequest line = new PurchaseOrderLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(BigDecimal.TEN);
        line.setCostPrice(new BigDecimal("100.00"));
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setCompanyId(company.getId());
        req.setWarehouseId(warehouse.getId());
        req.setSupplierId(supplier.getId());
        req.setOrderDate("2026-08-01");
        req.setLines(List.of(line));
        return req;
    }

    private PurchaseInvoiceRequest piRequest() {
        PurchaseInvoiceLineRequest line = new PurchaseInvoiceLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(BigDecimal.TEN);
        line.setCostPrice(new BigDecimal("100.00"));
        line.setDiscountPercentage(BigDecimal.TEN);
        PurchaseInvoiceFeeRequest fee = new PurchaseInvoiceFeeRequest();
        fee.setDescription("Freight");
        fee.setAmount(new BigDecimal("50.00"));
        PurchaseInvoiceRequest req = new PurchaseInvoiceRequest();
        req.setCompanyId(company.getId());
        req.setWarehouseId(warehouse.getId());
        req.setSupplierId(supplier.getId());
        req.setInvoiceDate("2026-09-01");
        req.setDiscountPercentage(BigDecimal.TEN);
        req.setFees(List.of(fee));
        req.setLines(List.of(line));
        return req;
    }
}
