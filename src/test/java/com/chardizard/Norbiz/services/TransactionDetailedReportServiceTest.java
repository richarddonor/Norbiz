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

// "<Transaction> - Detailed" reports. Runs against the configured Postgres; every test rolls back.
@SpringBootTest
@Transactional
class TransactionDetailedReportServiceTest {

    @Autowired TransactionDetailedReportService reportService;
    @Autowired DeliveryReceiptService deliveryReceiptService;
    @Autowired OutletReceiveService outletReceiveService;
    @Autowired CustomerService customerService;
    @Autowired CompanyRepository companyRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired ItemCategoryRepository itemCategoryRepository;
    @Autowired ItemRepository itemRepository;
    @Autowired UserRepository userRepository;
    @Autowired InventoryAdjustmentService inventoryAdjustmentService;
    @MockitoBean TransactionReferenceService transactionReferenceService;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private int referenceCounter = 0;

    private String user;
    private Company company;
    private Customer outlet;
    private Item item1;
    private Item item2;

    @BeforeEach
    void setUp() {
        when(transactionReferenceService.next(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2) + "-RPT-" + suffix + "-" + (++referenceCounter));

        company = new Company();
        company.setName("Report Co " + suffix);
        company = companyRepository.save(company);

        User u = new User();
        u.setUsername("rpt_user_" + suffix);
        u.setEmail(u.getUsername() + "@test.local");
        u.setPassword("x");
        u.setCompanies(Set.of(company));
        user = userRepository.save(u).getUsername();

        Warehouse main = new Warehouse();
        main.setCompany(company);
        main.setCode("MAIN" + suffix);
        main.setName("Main");
        main.setMain(true);
        warehouseRepository.save(main);

        CustomerRequest outletReq = new CustomerRequest();
        outletReq.setCompanyId(company.getId());
        outletReq.setCode("OUT" + suffix);
        outletReq.setName("Outlet " + suffix);
        outletReq.setType(CustomerType.OUTLET);
        outlet = customerService.create(outletReq, user);

        ItemCategory category = new ItemCategory();
        category.setCompany(company);
        category.setName("Rpt Category " + suffix);
        category = itemCategoryRepository.save(category);
        item1 = item(category, "RPT1-" + suffix, "Report Item One");
        item2 = item(category, "RPT2-" + suffix, "Report Item Two");

        // Opening stock — deliveries can't take the main warehouse's on-hand below zero.
        InventoryAdjustmentRequest opening = new InventoryAdjustmentRequest();
        opening.setCompanyId(company.getId());
        opening.setWarehouseId(main.getId());
        opening.setAdjustmentDate("2026-01-01");
        opening.setLines(List.of(adjustmentLine(item1), adjustmentLine(item2)));
        inventoryAdjustmentService.create(opening, user);
    }

    private static InventoryAdjustmentLineRequest adjustmentLine(Item item) {
        InventoryAdjustmentLineRequest line = new InventoryAdjustmentLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(new BigDecimal("100"));
        return line;
    }

    @Test
    void deliveryReceiptRowsAreLinesFlattenedWithTheirHeaderInEntryOrder() {
        DeliveryReceipt dr = deliveryReceiptService.create(drRequest(), user);

        Page<TransactionDetailedReportRow> page = reportService.find(DetailedReportType.DELIVERY_RECEIPT, user,
                new DetailedReportFilter(), false, PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(TransactionDetailedReportRow::getItemCode)
                .containsExactly(item1.getItemCode(), item2.getItemCode());
        TransactionDetailedReportRow first = page.getContent().getFirst();
        assertThat(first.getTransactionId()).isEqualTo(dr.getId());
        assertThat(first.getReferenceNumber()).isEqualTo(dr.getReferenceNumber());
        assertThat(first.getCounterpartyName()).isEqualTo(outlet.getName());
        assertThat(first.getWarehouseName()).isEqualTo("Main");
        assertThat(first.getLineNumber()).isEqualTo(1);
        // Selling price isn't cost-gated.
        assertThat(first.getPrice()).isEqualByComparingTo("12.50");
        assertThat(first.getAmount()).isEqualByComparingTo("50.00");
        assertThat(first.getOrigin()).isEqualTo(TransactionOrigin.NATIVE);
    }

    @Test
    void originFilterSeparatesNativeFromMigratedTransactions() {
        deliveryReceiptService.create(drRequest(), user);

        DetailedReportFilter migrated = new DetailedReportFilter();
        migrated.setOrigin(TransactionOrigin.MIGRATED);
        assertThat(reportService.find(DetailedReportType.DELIVERY_RECEIPT, user, migrated, false, PageRequest.of(0, 50))
                .getContent()).isEmpty();

        DetailedReportFilter nativeOnly = new DetailedReportFilter();
        nativeOnly.setOrigin(TransactionOrigin.NATIVE);
        assertThat(reportService.find(DetailedReportType.DELIVERY_RECEIPT, user, nativeOnly, false, PageRequest.of(0, 50))
                .getContent()).hasSize(2);
    }

    @Test
    void outletReceiveRowsCarryTheirSourceDeliveryReceiptAndFilterByIt() {
        DeliveryReceipt dr = deliveryReceiptService.create(drRequest(), user);
        OutletReceive or = outletReceiveService.create(orRequest(dr), user);

        DetailedReportFilter filter = new DetailedReportFilter();
        filter.setSourceReferenceNumber(dr.getReferenceNumber().toLowerCase());
        filter.setItemName("one");
        Page<TransactionDetailedReportRow> page = reportService.find(DetailedReportType.OUTLET_RECEIVE, user,
                filter, false, PageRequest.of(0, 50));

        assertThat(page.getContent()).hasSize(1);
        TransactionDetailedReportRow row = page.getContent().getFirst();
        assertThat(row.getTransactionId()).isEqualTo(or.getId());
        assertThat(row.getSourceReferenceNumber()).isEqualTo(dr.getReferenceNumber());
        assertThat(row.getWarehouseId()).isEqualTo(outlet.getWarehouse().getId());
        assertThat(row.getAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    void everyReportRunsWithEveryApplicableFilter() {
        for (DetailedReportType type : DetailedReportType.values()) {
            DetailedReportFilter filter = new DetailedReportFilter();
            filter.setWarehouseId(1L);
            filter.setItemId(1L);
            filter.setVoided(false);
            filter.setOrigin(TransactionOrigin.NATIVE);
            filter.setReferenceNumber("x");
            filter.setSheetNumber("x");
            filter.setWarehouse("x");
            filter.setItemCode("x");
            filter.setItemName("x");
            filter.setRemarks("x");
            filter.setDateFrom("2026-01-01");
            filter.setDateTo("2026-12-31");
            switch (type) {
                case PURCHASE_ORDER, PURCHASE_INVOICE, PURCHASE_RECEIVE -> { filter.setSupplierId(1L); filter.setCounterparty("x"); }
                case DELIVERY_RECEIPT, OUTLET_RECEIVE, STOCK_TRANSFER, OUTLET_PULL_OUT, PULL_OUT_RECEIVE -> { filter.setCustomerId(1L); filter.setCounterparty("x"); }
                case OUTLET_DELIVERY_RECEIPT, OUTLET_DELIVERY_RETURN -> { filter.setCustomerId(1L); filter.setCounterparty("x"); filter.setAgentId(1L); }
                case INVENTORY_ADJUSTMENT, ASSEMBLY -> { }
            }
            if (type == DetailedReportType.PURCHASE_INVOICE || type == DetailedReportType.PURCHASE_RECEIVE
                    || type == DetailedReportType.OUTLET_RECEIVE || type == DetailedReportType.OUTLET_DELIVERY_RETURN
                    || type == DetailedReportType.DELIVERY_RECEIPT || type == DetailedReportType.PULL_OUT_RECEIVE) {
                filter.setSourceReferenceNumber("x");
            }
            assertThat(reportService.find(type, user, filter, true, PageRequest.of(0, 50)).getContent())
                    .as(type.getDisplayName()).isEmpty();
        }
    }

    @Test
    void inapplicableCounterpartyFilterIsRejected() {
        DetailedReportFilter filter = new DetailedReportFilter();
        filter.setSupplierId(1L);
        assertThatThrownBy(() -> reportService.find(DetailedReportType.INVENTORY_ADJUSTMENT, user, filter, true, PageRequest.of(0, 50)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Inventory Adjustment - Detailed");
    }

    @Test
    void agentFilterIsRejectedWhereThereIsNoAgent() {
        DetailedReportFilter filter = new DetailedReportFilter();
        filter.setAgentId(1L);
        assertThatThrownBy(() -> reportService.find(DetailedReportType.DELIVERY_RECEIPT, user, filter, true, PageRequest.of(0, 50)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("agentId");
    }

    @Test
    void reportNamesAndPermissionsAreUniform() {
        assertThat(DetailedReportType.PURCHASE_ORDER.getDisplayName()).isEqualTo("Purchase Order - Detailed");
        assertThat(DetailedReportType.PURCHASE_ORDER.getPermission()).isEqualTo("VIEW_PURCHASE_ORDER_DETAILED_REPORT");
        assertThat(DetailedReportType.values()).hasSameSizeAs(TransactionType.values());
    }

    private Item item(ItemCategory category, String code, String name) {
        Item item = new Item();
        item.setCompany(company);
        item.setItemCategory(category);
        item.setItemCode(code);
        item.setName(name);
        item.getTags().add(ItemTag.INVENTORY);
        return itemRepository.save(item);
    }

    private DeliveryReceiptRequest drRequest() {
        DeliveryReceiptRequest req = new DeliveryReceiptRequest();
        req.setCompanyId(company.getId());
        req.setCustomerId(outlet.getId());
        req.setDeliveryDate("2026-10-04");
        req.setLines(new ArrayList<>(List.of(drLine(item1), drLine(item2))));
        return req;
    }

    private DeliveryReceiptLineRequest drLine(Item item) {
        DeliveryReceiptLineRequest line = new DeliveryReceiptLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(new BigDecimal("4"));
        line.setUnitPrice(new BigDecimal("12.50"));
        return line;
    }

    private OutletReceiveRequest orRequest(DeliveryReceipt dr) {
        List<OutletReceiveLineRequest> lines = new ArrayList<>();
        for (Item item : List.of(item1, item2)) {
            OutletReceiveLineRequest line = new OutletReceiveLineRequest();
            line.setItemId(item.getId());
            line.setQuantity(new BigDecimal("4"));
            lines.add(line);
        }
        OutletReceiveRequest req = new OutletReceiveRequest();
        req.setCompanyId(company.getId());
        req.setDeliveryReceiptId(dr.getId());
        req.setReceiptDate("2026-10-05");
        req.setLines(lines);
        return req;
    }
}
