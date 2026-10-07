package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.AssemblyMaterialRequest;
import com.chardizard.Norbiz.dto.AssemblyOutputRequest;
import com.chardizard.Norbiz.dto.AssemblyRequest;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import com.chardizard.Norbiz.util.DateRangeUtils;
import com.chardizard.Norbiz.util.SpecificationUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// Builds finished items from raw materials in the main warehouse (docs/TRANSACTIONS.md "Assembly"):
// OUTPUT lines add on-hand stock, MATERIAL lines deduct it, in one posting.
@Service
@RequiredArgsConstructor
public class AssemblyService {

    private static final Logger log = LoggerFactory.getLogger(AssemblyService.class);
    private static final String TRANSACTION_TYPE = TransactionType.ASSEMBLY.name();
    private static final String VOID_SOURCE_TYPE = "ASSEMBLY_VOID";
    private static final String REFERENCE_PREFIX = "ASM";

    private final AssemblyRepository assemblyRepository;
    private final BillOfMaterialRepository billOfMaterialRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final InventoryStockService inventoryStockService;
    private final CompanyRepository companyRepository;
    private final WarehouseRepository warehouseRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final TransactionReferenceService transactionReferenceService;
    private final TransactionEventService transactionEventService;

    public Page<Assembly> findAllForUser(String username, Map<String, String> filters, Instant dateFrom, Instant dateTo, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        Specification<Assembly> companyScope = null;
        if (!isSuperAdmin) {
            List<Long> companyIds = user.getCompanies().stream()
                    .map(Company::getId)
                    .collect(Collectors.toList());
            if (companyIds.isEmpty()) return Page.empty(pageable);
            companyScope = (root, query, cb) -> root.get("company").get("id").in(companyIds);
        }

        Specification<Assembly> spec = SpecificationUtils.allOf(
                companyScope,
                SpecificationUtils.containsIgnoreCase("referenceNumber", filters.get("referenceNumber")),
                SpecificationUtils.containsIgnoreCase("sheetNumber", filters.get("sheetNumber")),
                SpecificationUtils.dateRange("assemblyDate", dateFrom, dateTo),
                SpecificationUtils.enumEquals("origin", TransactionOrigin.class, filters.get("origin"))
        );

        return assemblyRepository.findAll(spec, pageable);
    }

    public Assembly findById(Long id, String username) {
        Assembly assembly = assemblyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assembly not found: " + id));
        assertCompanyAccess(username, assembly.getCompany().getId());
        return assembly;
    }

    @Transactional
    public Assembly create(AssemblyRequest request, String username) {
        Company company = companyRepository.findById(request.getCompanyId())
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + request.getCompanyId()));

        assertCompanyAccess(username, company.getId());

        Warehouse mainWarehouse = warehouseRepository.findFirstByCompanyIdAndMainTrue(company.getId())
                .orElseThrow(() -> new IllegalArgumentException("No main warehouse configured for company: " + company.getId()));
        if (!mainWarehouse.isActive()) {
            throw new IllegalArgumentException("Main warehouse is inactive: " + mainWarehouse.getName());
        }

        Instant assemblyDate = DateRangeUtils.startOfDayUtc(request.getAssemblyDate());
        if (assemblyDate == null) {
            throw new IllegalArgumentException("assemblyDate is required (yyyy-MM-dd)");
        }

        Instant now = Instant.now();

        Assembly assembly = new Assembly();
        assembly.setCompany(company);
        assembly.setWarehouse(mainWarehouse);
        assembly.setAssemblyDate(assemblyDate);
        assembly.setRemarks(request.getRemarks());
        assembly.setSheetNumber(request.getSheetNumber());
        assembly.setCreatedAt(now);
        assembly.setCreatedBy(username);

        // Outputs first, then materials — one line-number sequence, so the collection's order is entry order.
        int lineNumber = 1;
        for (AssemblyOutputRequest outputRequest : request.getOutputs()) {
            Item item = loadItem(outputRequest.getItemId(), company);
            AssemblyLine line = newLine(assembly, AssemblyLineKind.OUTPUT, item, outputRequest.getQuantity(), lineNumber++);
            if (outputRequest.getBillOfMaterialId() != null) {
                line.setBillOfMaterial(loadBillOfMaterial(outputRequest.getBillOfMaterialId(), company, item));
            }
        }
        for (AssemblyMaterialRequest materialRequest : request.getMaterials()) {
            Item item = loadItem(materialRequest.getItemId(), company);
            newLine(assembly, AssemblyLineKind.MATERIAL, item, materialRequest.getQuantity(), lineNumber++);
        }

        // Raw materials consumed can't take on-hand below zero (docs/INVENTORY.md "Negative stock").
        inventoryStockService.assertAvailable(mainWarehouse, assembly.getLines(), AssemblyLine::getItem,
                l -> l.getKind() == AssemblyLineKind.MATERIAL ? l.getQuantity() : BigDecimal.ZERO);

        // Generated last, only once validation has fully passed, to avoid burning
        // reference numbers on requests that were always going to be rejected.
        assembly.setReferenceNumber(transactionReferenceService.next(company.getId(), TRANSACTION_TYPE, REFERENCE_PREFIX));

        Assembly saved = assemblyRepository.save(assembly);
        transactionEventService.recordSystemEvent(company, TransactionType.ASSEMBLY, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.CREATED, username, saved.getCreatedAt());

        for (AssemblyLine line : saved.getLines()) {
            postMovement(TRANSACTION_TYPE, saved, line.getItem(), stockEffect(line), now, username);
        }

        log.info("User '{}' posted assembly (id={}) in main warehouse {}: {} output line(s), {} material line(s)",
                username, saved.getId(), mainWarehouse.getId(),
                saved.getLines().stream().filter(l -> l.getKind() == AssemblyLineKind.OUTPUT).count(),
                saved.getLines().stream().filter(l -> l.getKind() == AssemblyLineKind.MATERIAL).count());
        return saved;
    }

    // Voiding is the only sanctioned way to cancel an immutable transaction (docs/TRANSACTIONS.md "Voiding").
    // Nothing loads from an Assembly, but voiding takes the outputs back out of on-hand — rejected if they've
    // since been consumed — and returns the raw materials.
    @Transactional
    public Assembly voidAssembly(Long id, String username) {
        Assembly assembly = findById(id, username);

        if (assembly.isVoided()) {
            throw new IllegalArgumentException("Assembly already voided: " + id);
        }

        inventoryStockService.assertAvailable(assembly.getWarehouse(), assembly.getLines(), AssemblyLine::getItem,
                l -> l.getKind() == AssemblyLineKind.OUTPUT ? l.getQuantity() : BigDecimal.ZERO);

        Instant now = Instant.now();
        for (AssemblyLine line : assembly.getLines()) {
            postMovement(VOID_SOURCE_TYPE, assembly, line.getItem(), stockEffect(line).negate(), now, username);
        }

        assembly.setVoided(true);
        assembly.setVoidedAt(now);
        assembly.setVoidedBy(username);

        Assembly saved = assemblyRepository.save(assembly);
        transactionEventService.recordSystemEvent(saved.getCompany(), TransactionType.ASSEMBLY, saved.getId(), saved.getReferenceNumber(),
                TransactionEventType.VOIDED, username, saved.getVoidedAt());
        log.info("User '{}' voided assembly (id={})", username, id);
        return saved;
    }

    /** +quantity for an output, -quantity for a consumed raw material. */
    private static BigDecimal stockEffect(AssemblyLine line) {
        return line.getKind() == AssemblyLineKind.OUTPUT ? line.getQuantity() : line.getQuantity().negate();
    }

    private static AssemblyLine newLine(Assembly assembly, AssemblyLineKind kind, Item item, BigDecimal quantity, int lineNumber) {
        AssemblyLine line = new AssemblyLine();
        line.setAssembly(assembly);
        line.setKind(kind);
        line.setItem(item);
        line.setQuantity(quantity);
        line.setLineNumber(lineNumber);
        assembly.getLines().add(line);
        return line;
    }

    private Item loadItem(Long itemId, Company company) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));
        if (!item.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Item does not belong to company: " + company.getId());
        }
        if (!item.getTags().contains(ItemTag.INVENTORY)) {
            throw new IllegalArgumentException("Item is not inventory-tracked: " + item.getItemCode());
        }
        if (!item.isActive()) {
            throw new IllegalArgumentException("Item is inactive: " + item.getItemCode());
        }
        return item;
    }

    private BillOfMaterial loadBillOfMaterial(Long billOfMaterialId, Company company, Item output) {
        BillOfMaterial bom = billOfMaterialRepository.findById(billOfMaterialId)
                .orElseThrow(() -> new IllegalArgumentException("Bill of materials not found: " + billOfMaterialId));
        if (!bom.getCompany().getId().equals(company.getId())) {
            throw new IllegalArgumentException("Bill of materials does not belong to company: " + company.getId());
        }
        if (!bom.isActive()) {
            throw new IllegalArgumentException("Bill of materials is inactive: " + bom.getCode());
        }
        if (!bom.getItem().getId().equals(output.getId())) {
            throw new IllegalArgumentException("Bill of materials " + bom.getCode() + " doesn't produce item " + output.getItemCode());
        }
        return bom;
    }

    private void postMovement(String sourceType, Assembly assembly, Item item, BigDecimal quantityDelta, Instant now, String username) {
        Warehouse warehouse = assembly.getWarehouse();

        InventoryMovement movement = new InventoryMovement();
        movement.setCompany(assembly.getCompany());
        movement.setItem(item);
        movement.setWarehouse(warehouse);
        movement.setQuantityDelta(quantityDelta);
        movement.setTransitQuantityDelta(BigDecimal.ZERO);
        movement.setMovementDate(assembly.getAssemblyDate());
        movement.setSourceType(sourceType);
        movement.setSourceId(assembly.getId());
        movement.setReferenceNumber(assembly.getReferenceNumber());
        movement.setSheetNumber(assembly.getSheetNumber());
        movement.setCreatedAt(now);
        movement.setCreatedBy(username);
        inventoryMovementRepository.save(movement);

        inventoryStockService.apply(item, warehouse, quantityDelta, BigDecimal.ZERO, now);
    }

    private void assertCompanyAccess(String username, Long companyId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        boolean isSuperAdmin = user.getRoles().stream()
                .anyMatch(r -> r.getName().equals("SUPER_ADMIN"));

        if (isSuperAdmin) return;

        boolean hasAccess = user.getCompanies().stream()
                .anyMatch(c -> c.getId().equals(companyId));

        if (!hasAccess) {
            log.warn("User '{}' denied access to company {}", username, companyId);
            throw new SecurityException("Access denied to company: " + companyId);
        }
    }
}
