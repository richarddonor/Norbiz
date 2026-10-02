package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.InventoryAdjustmentLineRequest;
import com.chardizard.Norbiz.dto.InventoryAdjustmentRequest;
import com.chardizard.Norbiz.dto.TransactionActionDefinitionRequest;
import com.chardizard.Norbiz.dto.TransactionActionRequest;
import com.chardizard.Norbiz.dto.TransactionAvailableActionResponse;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
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
// TransactionReferenceService is mocked because its REQUIRES_NEW transaction can't see the
// test's uncommitted company (and would otherwise commit sequence rows to the real DB).
@SpringBootTest
@Transactional
class TransactionEventServiceTest {

    @Autowired TransactionEventService transactionEventService;
    @Autowired TransactionActionDefinitionService definitionService;
    @Autowired InventoryAdjustmentService inventoryAdjustmentService;
    @Autowired CompanyRepository companyRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired ItemCategoryRepository itemCategoryRepository;
    @Autowired ItemRepository itemRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired UserRepository userRepository;
    @MockitoBean TransactionReferenceService transactionReferenceService;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private Company company;
    private Company otherCompany;
    private Role distributorRole;
    private Role printerRole;
    private String alice;      // distributor + printer
    private String bob;        // distributor only
    private String outsider;   // printer, but other company
    private Long adjustmentId;
    private TransactionActionDefinition distribution;
    private TransactionActionDefinition printing;
    private TransactionActionDefinition encoded;

    @BeforeEach
    void setUp() {
        when(transactionReferenceService.next(any(), any(), any())).thenReturn("IA-TEST-" + suffix);

        company = company("TA Co " + suffix);
        otherCompany = company("TA Other " + suffix);

        Permission viewAdjustment = permissionRepository.findByName("VIEW_INVENTORY_ADJUSTMENT").orElseThrow();
        distributorRole = role("TA_DISTRIBUTOR_" + suffix, viewAdjustment);
        printerRole = role("TA_PRINTER_" + suffix, viewAdjustment);

        alice = user("ta_alice_" + suffix, company, distributorRole, printerRole);
        bob = user("ta_bob_" + suffix, company, distributorRole);
        outsider = user("ta_out_" + suffix, otherCompany, printerRole);

        Warehouse warehouse = new Warehouse();
        warehouse.setCompany(company);
        warehouse.setCode("TA" + suffix);
        warehouse.setName("TA Warehouse");
        warehouse = warehouseRepository.save(warehouse);

        ItemCategory category = new ItemCategory();
        category.setCompany(company);
        category.setName("TA Category " + suffix);
        category = itemCategoryRepository.save(category);

        Item item = new Item();
        item.setCompany(company);
        item.setItemCategory(category);
        item.setItemCode("TA-" + suffix);
        item.setName("TA Item");
        item.getTags().add(ItemTag.INVENTORY);
        item = itemRepository.save(item);

        InventoryAdjustmentLineRequest line = new InventoryAdjustmentLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(BigDecimal.TEN);
        InventoryAdjustmentRequest adjustment = new InventoryAdjustmentRequest();
        adjustment.setCompanyId(company.getId());
        adjustment.setWarehouseId(warehouse.getId());
        adjustment.setAdjustmentDate("2026-10-02");
        adjustment.setLines(List.of(line));
        adjustmentId = inventoryAdjustmentService.create(adjustment, alice).getId();

        distribution = definition("BARCODE_DISTRIBUTION", Set.of(distributorRole.getId()), Set.of());
        printing = definition("BARCODE_PRINTING", Set.of(printerRole.getId()), Set.of(distribution.getId()));
        encoded = definition("ENCODED_BY", Set.of(distributorRole.getId(), printerRole.getId()), Set.of());
    }

    @Test
    void createRecordsCreatedEvent() {
        var history = transactionEventService.findHistory(TransactionType.INVENTORY_ADJUSTMENT, adjustmentId, alice, PageRequest.of(0, 50));
        assertThat(history.getContent()).extracting(TransactionEvent::getEventType).containsExactly(TransactionEventType.CREATED);
        assertThat(history.getContent().getFirst().getPerformedBy()).isEqualTo(alice);
    }

    @Test
    void sameUserCannotTakeSameActionTwiceButCanTakeOthers() {
        take(alice, distribution);
        assertThatThrownBy(() -> take(alice, distribution))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already taken");
        take(alice, encoded);
    }

    @Test
    void differentUsersCanTakeSameAction() {
        take(alice, distribution);
        take(bob, distribution);
        assertThat(actionsFor(alice).stream().filter(a -> a.getCode().equals("BARCODE_DISTRIBUTION")).findFirst().orElseThrow()
                .getTakenBy()).containsExactlyInAnyOrder(alice, bob);
    }

    @Test
    void prerequisiteMustBeTakenFirstByAnyUser() {
        assertThatThrownBy(() -> take(alice, printing))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requires these actions first");
        take(bob, distribution);
        take(alice, printing);
    }

    @Test
    void roleNotAllowedIsForbidden() {
        take(alice, distribution);
        assertThatThrownBy(() -> take(bob, printing)).isInstanceOf(SecurityException.class);
        TransactionAvailableActionResponse printingForBob = actionsFor(bob).stream()
                .filter(a -> a.getCode().equals("BARCODE_PRINTING")).findFirst().orElseThrow();
        assertThat(printingForBob.isAllowedForMe()).isFalse();
        assertThat(printingForBob.isCanTake()).isFalse();
    }

    @Test
    void otherCompanyUserIsForbidden() {
        assertThatThrownBy(() -> take(outsider, encoded)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> transactionEventService.findHistory(
                TransactionType.INVENTORY_ADJUSTMENT, adjustmentId, outsider, PageRequest.of(0, 50)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void voidRecordsVoidedEventAndBlocksActions() {
        take(alice, distribution);
        inventoryAdjustmentService.voidAdjustment(adjustmentId, bob);

        var history = transactionEventService.findHistory(TransactionType.INVENTORY_ADJUSTMENT, adjustmentId, alice, PageRequest.of(0, 50));
        assertThat(history.getContent()).extracting(TransactionEvent::getEventType)
                .containsExactly(TransactionEventType.CREATED, TransactionEventType.ACTION, TransactionEventType.VOIDED);
        assertThatThrownBy(() -> take(alice, encoded))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("voided");
    }

    @Test
    void prerequisiteCycleIsRejected() {
        TransactionActionDefinitionRequest request = request("BARCODE_DISTRIBUTION", Set.of(distributorRole.getId()), Set.of(printing.getId()));
        assertThatThrownBy(() -> definitionService.update(distribution.getId(), request, alice))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("circular");
    }

    @Test
    void definitionInUseCannotBeDeleted() {
        assertThatThrownBy(() -> definitionService.delete(distribution.getId(), alice))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("prerequisite");
        take(alice, encoded);
        assertThatThrownBy(() -> definitionService.delete(encoded.getId(), alice))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deactivate");
    }

    private void take(String username, TransactionActionDefinition definition) {
        TransactionActionRequest request = new TransactionActionRequest();
        request.setActionDefinitionId(definition.getId());
        transactionEventService.takeAction(TransactionType.INVENTORY_ADJUSTMENT, adjustmentId, request, username);
    }

    private List<TransactionAvailableActionResponse> actionsFor(String username) {
        return transactionEventService.findAvailableActions(TransactionType.INVENTORY_ADJUSTMENT, adjustmentId, username);
    }

    private TransactionActionDefinition definition(String code, Set<Long> roleIds, Set<Long> prerequisiteIds) {
        return definitionService.create(request(code, roleIds, prerequisiteIds), alice);
    }

    private TransactionActionDefinitionRequest request(String code, Set<Long> roleIds, Set<Long> prerequisiteIds) {
        TransactionActionDefinitionRequest request = new TransactionActionDefinitionRequest();
        request.setCompanyId(company.getId());
        request.setTransactionType(TransactionType.INVENTORY_ADJUSTMENT);
        request.setCode(code);
        request.setName(code.replace('_', ' '));
        request.setSortOrder(0);
        request.setActive(true);
        request.setAllowedRoleIds(roleIds);
        request.setPrerequisiteIds(prerequisiteIds);
        return request;
    }

    private Company company(String name) {
        Company c = new Company();
        c.setName(name);
        return companyRepository.save(c);
    }

    private Role role(String name, Permission... permissions) {
        Role r = new Role();
        r.setName(name);
        r.setPermissions(Set.of(permissions));
        return roleRepository.save(r);
    }

    private String user(String username, Company company, Role... roles) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@test.local");
        u.setPassword("x");
        u.setCompanies(Set.of(company));
        u.setRoles(Set.of(roles));
        userRepository.save(u);
        return username;
    }
}
