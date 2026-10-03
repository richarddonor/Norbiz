package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.PurchaseOrderLineRequest;
import com.chardizard.Norbiz.dto.PurchaseOrderRequest;
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

// Runs against the configured Postgres (same as NorbizApplicationTests); every test rolls back.
// TransactionReferenceService is mocked for the same reason as in TransactionEventServiceTest.
@SpringBootTest
@Transactional
class InactiveReferenceTest {

    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired CompanyRepository companyRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired ItemCategoryRepository itemCategoryRepository;
    @Autowired ItemRepository itemRepository;
    @Autowired UserRepository userRepository;
    @MockitoBean TransactionReferenceService transactionReferenceService;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private String buyer;
    private Company company;
    private Warehouse warehouse;
    private Supplier supplier;
    private Item item;

    @BeforeEach
    void setUp() {
        when(transactionReferenceService.next(any(), any(), any())).thenReturn("PO-TEST-" + suffix);

        company = new Company();
        company.setName("IR Co " + suffix);
        company = companyRepository.save(company);

        User u = new User();
        u.setUsername("ir_buyer_" + suffix);
        u.setEmail(u.getUsername() + "@test.local");
        u.setPassword("x");
        u.setCompanies(Set.of(company));
        buyer = userRepository.save(u).getUsername();

        warehouse = new Warehouse();
        warehouse.setCompany(company);
        warehouse.setCode("IR" + suffix);
        warehouse.setName("IR Warehouse");
        warehouse = warehouseRepository.save(warehouse);

        supplier = new Supplier();
        supplier.setCompany(company);
        supplier.setCode("IR" + suffix);
        supplier.setName("IR Supplier");
        supplier = supplierRepository.save(supplier);

        ItemCategory category = new ItemCategory();
        category.setCompany(company);
        category.setName("IR Category " + suffix);
        category = itemCategoryRepository.save(category);

        item = new Item();
        item.setCompany(company);
        item.setItemCategory(category);
        item.setItemCode("IR-" + suffix);
        item.setName("IR Item");
        item.getTags().add(ItemTag.INVENTORY);
        item = itemRepository.save(item);
    }

    @Test
    void activeReferencesAreAccepted() {
        assertThat(purchaseOrderService.create(request(), buyer, false).getId()).isNotNull();
    }

    @Test
    void inactiveSupplierIsRejected() {
        supplier.setActive(false);
        assertThatThrownBy(() -> purchaseOrderService.create(request(), buyer, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Supplier is inactive");
    }

    @Test
    void inactiveWarehouseIsRejected() {
        warehouse.setActive(false);
        assertThatThrownBy(() -> purchaseOrderService.create(request(), buyer, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Warehouse is inactive");
    }

    @Test
    void inactiveItemIsRejected() {
        item.setActive(false);
        assertThatThrownBy(() -> purchaseOrderService.create(request(), buyer, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Item is inactive");
    }

    private PurchaseOrderRequest request() {
        PurchaseOrderLineRequest line = new PurchaseOrderLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(BigDecimal.TEN);
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setCompanyId(company.getId());
        req.setWarehouseId(warehouse.getId());
        req.setSupplierId(supplier.getId());
        req.setOrderDate("2026-10-03");
        req.setLines(List.of(line));
        return req;
    }
}
