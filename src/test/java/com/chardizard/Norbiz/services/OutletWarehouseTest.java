package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.CustomerRequest;
import com.chardizard.Norbiz.dto.WarehouseRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Runs against the configured Postgres (same as NorbizApplicationTests); every test rolls back.
@SpringBootTest
@Transactional
class OutletWarehouseTest {

    @Autowired CustomerService customerService;
    @Autowired WarehouseService warehouseService;
    @Autowired CompanyRepository companyRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired UserRepository userRepository;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private String user;
    private Company company;

    @BeforeEach
    void setUp() {
        company = new Company();
        company.setName("OW Co " + suffix);
        company = companyRepository.save(company);

        User u = new User();
        u.setUsername("ow_user_" + suffix);
        u.setEmail(u.getUsername() + "@test.local");
        u.setPassword("x");
        u.setCompanies(Set.of(company));
        user = userRepository.save(u).getUsername();
    }

    @Test
    void creatingAnOutletCreatesAndLinksItsOwnWarehouse() {
        Customer outlet = customerService.create(customerRequest("OUT" + suffix, CustomerType.OUTLET), user);

        Warehouse warehouse = outlet.getWarehouse();
        assertThat(warehouse).isNotNull();
        assertThat(warehouse.getId()).isNotNull();
        assertThat(warehouse.isOutlet()).isTrue();
        assertThat(warehouse.isMain()).isFalse();
        assertThat(warehouse.getName()).isEqualTo(outlet.getName());
        assertThat(warehouse.getCode()).isEqualTo(outlet.getCode());
        assertThat(warehouse.getCompany().getId()).isEqualTo(company.getId());
    }

    @Test
    void plainCustomerGetsNoWarehouse() {
        Customer customer = customerService.create(customerRequest("CUS" + suffix, CustomerType.CUSTOMER), user);
        assertThat(customer.getWarehouse()).isNull();
    }

    @Test
    void outletCannotBeTurnedBackIntoACustomer() {
        Customer outlet = customerService.create(customerRequest("OUT" + suffix, CustomerType.OUTLET), user);
        assertThatThrownBy(() -> customerService.update(outlet.getId(), customerRequest("OUT" + suffix, CustomerType.CUSTOMER), user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cannot change an outlet");
    }

    @Test
    void customerTurnedOutletGetsAWarehouseAndRenamesSyncIt() {
        Customer customer = customerService.create(customerRequest("CUS" + suffix, CustomerType.CUSTOMER), user);
        CustomerRequest req = customerRequest("CUS" + suffix, CustomerType.OUTLET);
        Customer outlet = customerService.update(customer.getId(), req, user);
        assertThat(outlet.getWarehouse()).isNotNull();

        req.setName("Renamed " + suffix);
        Customer renamed = customerService.update(outlet.getId(), req, user);
        assertThat(renamed.getWarehouse().getName()).isEqualTo("Renamed " + suffix);
    }

    @Test
    void outletWarehouseCannotBeEditedOrMadeMainDirectly() {
        Warehouse warehouse = customerService.create(customerRequest("OUT" + suffix, CustomerType.OUTLET), user).getWarehouse();
        assertThatThrownBy(() -> warehouseService.update(warehouse.getId(), warehouseRequest("X" + suffix, true), user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("managed through its outlet customer");
        assertThatThrownBy(() -> warehouseService.delete(warehouse.getId(), user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("managed through its outlet customer");
    }

    @Test
    void settingMainMovesTheFlag() {
        Warehouse first = warehouseService.create(warehouseRequest("W1" + suffix, true), user);
        Warehouse second = warehouseService.create(warehouseRequest("W2" + suffix, true), user);

        assertThat(warehouseRepository.findById(first.getId()).orElseThrow().isMain()).isFalse();
        assertThat(warehouseRepository.findById(second.getId()).orElseThrow().isMain()).isTrue();
        assertThat(warehouseRepository.findFirstByCompanyIdAndMainTrue(company.getId())).get()
                .extracting(Warehouse::getId).isEqualTo(second.getId());
    }

    @Test
    void customerListIncludesActiveAndInactiveUnlessFiltered() {
        Customer active = customerService.create(customerRequest("ACT" + suffix, CustomerType.CUSTOMER), user);
        CustomerRequest inactiveReq = customerRequest("INA" + suffix, CustomerType.CUSTOMER);
        inactiveReq.setActive(false);
        Customer inactive = customerService.create(inactiveReq, user);

        assertThat(customerService.findAllForUser(user, Map.of(), null, null, PageRequest.of(0, 50)).getContent())
                .extracting(Customer::getId).contains(active.getId(), inactive.getId());
        assertThat(customerService.findAllForUser(user, Map.of("active", "true"), null, null, PageRequest.of(0, 50)).getContent())
                .extracting(Customer::getId).contains(active.getId()).doesNotContain(inactive.getId());
        assertThat(customerService.findAllForUser(user, Map.of("active", "false"), null, null, PageRequest.of(0, 50)).getContent())
                .extracting(Customer::getId).contains(inactive.getId()).doesNotContain(active.getId());
    }

    private CustomerRequest customerRequest(String code, CustomerType type) {
        CustomerRequest req = new CustomerRequest();
        req.setCompanyId(company.getId());
        req.setCode(code);
        req.setName("Name " + code);
        req.setType(type);
        return req;
    }

    private WarehouseRequest warehouseRequest(String code, boolean main) {
        WarehouseRequest req = new WarehouseRequest();
        req.setCompanyId(company.getId());
        req.setCode(code);
        req.setName("Warehouse " + code);
        req.setMain(main);
        return req;
    }
}
