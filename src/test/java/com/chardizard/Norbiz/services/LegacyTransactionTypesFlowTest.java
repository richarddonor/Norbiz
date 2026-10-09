package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.exceptions.InsufficientStockException;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

// Ledger effects of the transaction types added for the legacy (jbsKarutora) migration: Stock Transfer
// (and the Delivery Receipt that loads it), Outlet Pull Out + Pull Out Receive, and Assembly with Bills of
// Materials. Runs against the configured Postgres; every test rolls back. TransactionReferenceService is
// mocked for the same reason as in TransactionEventServiceTest.
@SpringBootTest
@Transactional
class LegacyTransactionTypesFlowTest {

    @Autowired StockTransferService stockTransferService;
    @Autowired DashboardService dashboardService;
    @Autowired DeliveryReceiptService deliveryReceiptService;
    @Autowired OutletReceiveService outletReceiveService;
    @Autowired OutletPullOutService outletPullOutService;
    @Autowired PullOutReceiveService pullOutReceiveService;
    @Autowired AssemblyService assemblyService;
    @Autowired BillOfMaterialService billOfMaterialService;
    @Autowired PullOutReasonService pullOutReasonService;
    @Autowired InventoryAdjustmentService inventoryAdjustmentService;
    @Autowired CustomerService customerService;
    @Autowired CompanyRepository companyRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired ItemCategoryRepository itemCategoryRepository;
    @Autowired ItemRepository itemRepository;
    @Autowired InventoryBalanceRepository inventoryBalanceRepository;
    @Autowired UserRepository userRepository;
    @MockitoBean TransactionReferenceService transactionReferenceService;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private int referenceCounter = 0;

    private String user;
    private Company company;
    private Warehouse mainWarehouse;
    private Customer outlet;
    private Customer otherOutlet;
    private ItemCategory category;
    private Item item;
    private Item material;
    private PullOutReason reason;

    @BeforeEach
    void setUp() {
        when(transactionReferenceService.next(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2) + "-TEST-" + suffix + "-" + (++referenceCounter));

        company = new Company();
        company.setName("Legacy Types Co " + suffix);
        company = companyRepository.save(company);

        User u = new User();
        u.setUsername("lt_user_" + suffix);
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

        outlet = customerService.create(customerRequest("OUT" + suffix), user);
        otherOutlet = customerService.create(customerRequest("OUT2" + suffix), user);

        category = new ItemCategory();
        category.setCompany(company);
        category.setName("LT Category " + suffix);
        category = itemCategoryRepository.save(category);

        item = item("RING-" + suffix);
        material = item("WIRE-" + suffix);

        PullOutReasonRequest reasonRequest = new PullOutReasonRequest();
        reasonRequest.setCompanyId(company.getId());
        reasonRequest.setName("Damaged");
        reason = pullOutReasonService.create(reasonRequest, user);

        stock(mainWarehouse, item, "100");
    }

    // ---- Stock Transfer → Delivery Receipt ----

    @Test
    void stockTransferHoldsStockAsNegativeMainTransit() {
        StockTransfer st = stockTransferService.create(stRequest(outlet, "10"), user);

        assertThat(st.getOrigin()).isEqualTo(TransactionOrigin.NATIVE);
        assertBalance(mainWarehouse, item, "100", "-10");
    }

    @Test
    void deliveringATransferCopiesItsLinesReleasesTheHoldAndLoadsIt() {
        StockTransfer st = stockTransferService.create(stRequest(outlet, "10"), user);

        DeliveryReceipt dr = deliveryReceiptService.create(drFromTransfer(st, outlet), user);

        assertThat(dr.getStockTransfer().getId()).isEqualTo(st.getId());
        assertThat(dr.getLines()).hasSize(1);
        assertThat(dr.getLines().getFirst().getQuantity()).isEqualByComparingTo("10");
        assertThat(dr.getLines().getFirst().getUnitPrice()).isEqualByComparingTo("7.50");
        assertThat(st.isLoaded()).isTrue();
        assertBalance(mainWarehouse, item, "90", "0");
        assertBalance(outlet.getWarehouse(), item, "0", "10");
    }

    @Test
    void deliveredTransferCantBeVoidedUntilTheDeliveryIsVoided() {
        StockTransfer st = stockTransferService.create(stRequest(outlet, "10"), user);
        DeliveryReceipt dr = deliveryReceiptService.create(drFromTransfer(st, outlet), user);

        assertThatThrownBy(() -> stockTransferService.voidStockTransfer(st.getId(), user))
                .hasMessageContaining("delivered");

        deliveryReceiptService.voidDeliveryReceipt(dr.getId(), user);
        assertThat(st.isLoaded()).isFalse();
        assertBalance(mainWarehouse, item, "100", "-10");

        stockTransferService.voidStockTransfer(st.getId(), user);
        assertBalance(mainWarehouse, item, "100", "0");
    }

    @Test
    void transferForAnotherCustomerOrWithExtraLinesIsRejected() {
        StockTransfer st = stockTransferService.create(stRequest(outlet, "10"), user);

        assertThatThrownBy(() -> deliveryReceiptService.create(drFromTransfer(st, otherOutlet), user))
                .hasMessageContaining("different customer");

        DeliveryReceiptRequest withLines = drFromTransfer(st, outlet);
        DeliveryReceiptLineRequest line = new DeliveryReceiptLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(BigDecimal.ONE);
        withLines.setLines(new ArrayList<>(List.of(line)));
        assertThatThrownBy(() -> deliveryReceiptService.create(withLines, user))
                .hasMessageContaining("lines must be omitted");
    }

    @Test
    void deliveryWithoutTransferOrLinesIsRejected() {
        DeliveryReceiptRequest req = new DeliveryReceiptRequest();
        req.setCompanyId(company.getId());
        req.setCustomerId(outlet.getId());
        req.setDeliveryDate("2026-10-04");
        assertThatThrownBy(() -> deliveryReceiptService.create(req, user)).hasMessageContaining("lines are required");
    }

    @Test
    void transferBeyondOnHandIsRejected() {
        assertThatThrownBy(() -> stockTransferService.create(stRequest(outlet, "101"), user))
                .isInstanceOf(InsufficientStockException.class);
    }

    // ---- Outlet Pull Out → Pull Out Receive ----

    @Test
    void pullOutMovesOutletStockIntoMainTransitThenReceiveTakesItOnHand() {
        stockOutlet("20"); // main 100 -> 80, outlet 20

        OutletPullOut po = outletPullOutService.create(poRequest("8"), user);
        assertThat(po.getWarehouse().getId()).isEqualTo(outlet.getWarehouse().getId());
        assertThat(po.getDestinationWarehouse().getId()).isEqualTo(mainWarehouse.getId());
        assertBalance(outlet.getWarehouse(), item, "12", "0");
        assertBalance(mainWarehouse, item, "80", "8");

        pullOutReceiveService.create(porRequest(po, "5"), user);
        assertThat(po.isLoaded()).isFalse();
        assertBalance(mainWarehouse, item, "85", "3");

        pullOutReceiveService.create(porRequest(po, "3"), user);
        assertThat(po.isLoaded()).isTrue();
        assertBalance(mainWarehouse, item, "88", "0");
    }

    @Test
    void pullOutsAwaitingReceiveWidgetTracksTheOutstandingQuantityByReason() {
        stockOutlet("20");
        OutletPullOut partial = outletPullOutService.create(poRequest("8"), user);    // 2026-10-06
        pullOutReceiveService.create(porRequest(partial, "3"), user);                 // 5 left
        OutletPullOut full = outletPullOutService.create(poRequest("2"), user);
        pullOutReceiveService.create(porRequest(full, "2"), user);                    // fully received: excluded

        BacklogResponse widget = dashboardService.pullOutsAwaitingReceive(user, company.getId(), java.time.LocalDate.parse("2026-10-08"));

        assertThat(widget.documentCount()).isEqualTo(1);
        assertThat(widget.outstandingQuantity()).isEqualByComparingTo("5");
        assertThat(widget.progressPercent()).isEqualByComparingTo("37.5");
        assertThat(widget.oldestAgeDays()).isEqualTo(2);
        assertThat(widget.breakdown()).singleElement().satisfies(b -> {
            assertThat(b.name()).isEqualTo("Damaged");
            assertThat(b.quantity()).isEqualByComparingTo("5");
        });
        assertThat(widget.oldest()).singleElement().satisfies(d -> {
            assertThat(d.id()).isEqualTo(partial.getId());
            assertThat(d.group()).isEqualTo("Damaged");
        });
    }

    @Test
    void receivedPullOutCantBeVoidedUntilTheReceiveIsVoided() {
        stockOutlet("20");
        OutletPullOut po = outletPullOutService.create(poRequest("8"), user);
        PullOutReceive receive = pullOutReceiveService.create(porRequest(po, "8"), user);

        assertThatThrownBy(() -> outletPullOutService.voidOutletPullOut(po.getId(), user))
                .hasMessageContaining("received");

        pullOutReceiveService.voidPullOutReceive(receive.getId(), user);
        assertThat(po.isLoaded()).isFalse();
        assertBalance(mainWarehouse, item, "80", "8");

        outletPullOutService.voidOutletPullOut(po.getId(), user);
        assertBalance(outlet.getWarehouse(), item, "20", "0");
        assertBalance(mainWarehouse, item, "80", "0");
    }

    @Test
    void overReceivingAPullOutIsRejected() {
        stockOutlet("20");
        OutletPullOut po = outletPullOutService.create(poRequest("8"), user);

        assertThatThrownBy(() -> pullOutReceiveService.create(porRequest(po, "9"), user))
                .hasMessageContaining("exceeds outstanding");
    }

    @Test
    void pullOutBeyondOutletOnHandIsRejected() {
        stockOutlet("5");
        assertThatThrownBy(() -> outletPullOutService.create(poRequest("6"), user))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void inactiveReasonIsRejected() {
        PullOutReasonRequest update = new PullOutReasonRequest();
        update.setCompanyId(company.getId());
        update.setName("Damaged");
        update.setActive(false);
        pullOutReasonService.update(reason.getId(), update, user);
        stockOutlet("5");

        assertThatThrownBy(() -> outletPullOutService.create(poRequest("1"), user))
                .hasMessageContaining("inactive");
    }

    // ---- Assembly ----

    @Test
    void assemblyAddsOutputsAndConsumesMaterialsAndVoidReversesBoth() {
        stock(mainWarehouse, material, "50");
        BillOfMaterial bom = billOfMaterialService.create(bomRequest(item, material, "2"), user);

        Assembly assembly = assemblyService.create(assemblyRequest(item, "5", bom.getId(), material, "10"), user);
        assertThat(assembly.getLines()).extracting(AssemblyLine::getKind)
                .containsExactly(AssemblyLineKind.OUTPUT, AssemblyLineKind.MATERIAL);
        assertBalance(mainWarehouse, item, "105", "0");
        assertBalance(mainWarehouse, material, "40", "0");

        assemblyService.voidAssembly(assembly.getId(), user);
        assertBalance(mainWarehouse, item, "100", "0");
        assertBalance(mainWarehouse, material, "50", "0");
    }

    @Test
    void assemblyShortOfMaterialsIsRejected() {
        stock(mainWarehouse, material, "3");
        assertThatThrownBy(() -> assemblyService.create(assemblyRequest(item, "1", null, material, "4"), user))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void billOfMaterialsForAnotherItemIsRejected() {
        stock(mainWarehouse, material, "50");
        Item other = item("PENDANT-" + suffix);
        BillOfMaterial bom = billOfMaterialService.create(bomRequest(other, material, "1"), user);

        assertThatThrownBy(() -> assemblyService.create(assemblyRequest(item, "1", bom.getId(), material, "1"), user))
                .hasMessageContaining("doesn't produce");
    }

    @Test
    void billOfMaterialsRejectsItsOwnOutputAsAComponent() {
        assertThatThrownBy(() -> billOfMaterialService.create(bomRequest(item, item, "1"), user))
                .hasMessageContaining("own bill of materials");
    }

    // ---- helpers ----

    private Item item(String code) {
        Item i = new Item();
        i.setCompany(company);
        i.setItemCategory(category);
        i.setItemCode(code);
        i.setName("Item " + code);
        i.getTags().add(ItemTag.INVENTORY);
        return itemRepository.save(i);
    }

    private void stock(Warehouse warehouse, Item stocked, String quantity) {
        InventoryAdjustmentLineRequest line = new InventoryAdjustmentLineRequest();
        line.setItemId(stocked.getId());
        line.setQuantity(new BigDecimal(quantity));
        InventoryAdjustmentRequest req = new InventoryAdjustmentRequest();
        req.setCompanyId(company.getId());
        req.setWarehouseId(warehouse.getId());
        req.setAdjustmentDate("2026-01-01");
        req.setLines(List.of(line));
        inventoryAdjustmentService.create(req, user);
    }

    // Outlet stock the way it really arrives: delivered from main, then received at the outlet.
    private void stockOutlet(String quantity) {
        StockTransfer st = stockTransferService.create(stRequest(outlet, quantity), user);
        DeliveryReceipt dr = deliveryReceiptService.create(drFromTransfer(st, outlet), user);
        OutletReceiveLineRequest line = new OutletReceiveLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(new BigDecimal(quantity));
        OutletReceiveRequest req = new OutletReceiveRequest();
        req.setCompanyId(company.getId());
        req.setDeliveryReceiptId(dr.getId());
        req.setReceiptDate("2026-10-05");
        req.setLines(List.of(line));
        outletReceiveService.create(req, user);
    }

    private void assertBalance(Warehouse warehouse, Item balanced, String quantity, String transitQuantity) {
        InventoryBalance balance = inventoryBalanceRepository.findByItemIdAndWarehouseId(balanced.getId(), warehouse.getId()).orElseThrow();
        assertThat(balance.getQuantity()).as("quantity in " + warehouse.getName()).isEqualByComparingTo(quantity);
        assertThat(balance.getTransitQuantity()).as("transit in " + warehouse.getName()).isEqualByComparingTo(transitQuantity);
    }

    private CustomerRequest customerRequest(String code) {
        CustomerRequest req = new CustomerRequest();
        req.setCompanyId(company.getId());
        req.setCode(code);
        req.setName("Name " + code);
        req.setType(CustomerType.OUTLET);
        return req;
    }

    private StockTransferRequest stRequest(Customer to, String quantity) {
        StockTransferLineRequest line = new StockTransferLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(new BigDecimal(quantity));
        line.setUnitPrice(new BigDecimal("7.50"));
        StockTransferRequest req = new StockTransferRequest();
        req.setCompanyId(company.getId());
        req.setCustomerId(to.getId());
        req.setTransferDate("2026-10-03");
        req.setLines(List.of(line));
        return req;
    }

    private DeliveryReceiptRequest drFromTransfer(StockTransfer st, Customer to) {
        DeliveryReceiptRequest req = new DeliveryReceiptRequest();
        req.setCompanyId(company.getId());
        req.setCustomerId(to.getId());
        req.setStockTransferId(st.getId());
        req.setDeliveryDate("2026-10-04");
        return req;
    }

    private OutletPullOutRequest poRequest(String quantity) {
        OutletPullOutLineRequest line = new OutletPullOutLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(new BigDecimal(quantity));
        OutletPullOutRequest req = new OutletPullOutRequest();
        req.setCompanyId(company.getId());
        req.setCustomerId(outlet.getId());
        req.setPullOutReasonId(reason.getId());
        req.setPullOutDate("2026-10-06");
        req.setLines(List.of(line));
        return req;
    }

    private PullOutReceiveRequest porRequest(OutletPullOut po, String quantity) {
        PullOutReceiveLineRequest line = new PullOutReceiveLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(new BigDecimal(quantity));
        PullOutReceiveRequest req = new PullOutReceiveRequest();
        req.setCompanyId(company.getId());
        req.setOutletPullOutId(po.getId());
        req.setReceiptDate("2026-10-07");
        req.setLines(List.of(line));
        return req;
    }

    private BillOfMaterialRequest bomRequest(Item output, Item component, String perUnit) {
        BillOfMaterialLineRequest line = new BillOfMaterialLineRequest();
        line.setItemId(component.getId());
        line.setQuantity(new BigDecimal(perUnit));
        BillOfMaterialRequest req = new BillOfMaterialRequest();
        req.setCompanyId(company.getId());
        req.setCode("BOM-" + output.getItemCode());
        req.setItemId(output.getId());
        req.setComponents(List.of(line));
        return req;
    }

    private AssemblyRequest assemblyRequest(Item output, String outputQuantity, Long bomId, Item consumed, String consumedQuantity) {
        AssemblyOutputRequest out = new AssemblyOutputRequest();
        out.setItemId(output.getId());
        out.setQuantity(new BigDecimal(outputQuantity));
        out.setBillOfMaterialId(bomId);
        AssemblyMaterialRequest mat = new AssemblyMaterialRequest();
        mat.setItemId(consumed.getId());
        mat.setQuantity(new BigDecimal(consumedQuantity));
        AssemblyRequest req = new AssemblyRequest();
        req.setCompanyId(company.getId());
        req.setAssemblyDate("2026-10-08");
        req.setOutputs(List.of(out));
        req.setMaterials(List.of(mat));
        return req;
    }
}
