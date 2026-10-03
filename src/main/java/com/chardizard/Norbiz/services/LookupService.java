package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.cache.CacheRegion;
import com.chardizard.Norbiz.cache.CacheScope;
import com.chardizard.Norbiz.cache.QueryCache;
import com.chardizard.Norbiz.dto.ItemLookupResponse;
import com.chardizard.Norbiz.dto.LookupResponse;
import com.chardizard.Norbiz.dto.StockLookupResponse;
import com.chardizard.Norbiz.dto.TransactionLookupResponse;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import com.chardizard.Norbiz.util.SpecificationUtils;
import jakarta.persistence.criteria.Join;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.chardizard.Norbiz.cache.QueryCache.params;

/**
 * Backs the slim {@code /lookups/*} dropdown endpoints. Every list is scoped to exactly one company
 * (the form's company, defaulting to the session's X-Company-Id), access-checked against the caller's
 * memberships (SUPER_ADMIN bypasses), and every by-id read verifies company membership — opening
 * these endpoints to more permissions (see LookupAccess) must not open them across tenants.
 *
 * List results are cached in Redis per company (see docs/CACHING.md); the access check always runs
 * before the cache is consulted. By-id reads are plain primary-key fetches and are not cached.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LookupService {

    private static final Logger log = LoggerFactory.getLogger(LookupService.class);

    private final UserRepository userRepository;
    private final SupplierRepository supplierRepository;
    private final CustomerRepository customerRepository;
    private final WarehouseRepository warehouseRepository;
    private final ItemRepository itemRepository;
    private final ItemCategoryRepository itemCategoryRepository;
    private final ItemGroupRepository itemGroupRepository;
    private final EmployeeRepository employeeRepository;
    private final RoleRepository roleRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseInvoiceRepository purchaseInvoiceRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final QueryCache queryCache;

    // ---- master data ----

    public Page<LookupResponse> suppliers(String username, Long companyId, String q, boolean activeOnly, Pageable pageable) {
        return search(CacheRegion.LOOKUP_SUPPLIER, params("q", q, "activeOnly", activeOnly), LookupResponse.class,
                supplierRepository, username, companyId, Supplier.class,
                SpecificationUtils.allOf(
                        SpecificationUtils.anyContainsIgnoreCase(q, "code", "name"),
                        activeOnly ? SpecificationUtils.booleanEquals("active", true) : null),
                withDefaultSort(pageable, "name"), this::toLookup);
    }

    public LookupResponse supplier(Long id, String username) {
        Supplier s = supplierRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Supplier not found: " + id));
        assertCompanyAccess(username, s.getCompany().getId());
        return toLookup(s);
    }

    public Page<LookupResponse> customers(String username, Long companyId, String q, boolean activeOnly, Pageable pageable) {
        return search(CacheRegion.LOOKUP_CUSTOMER, params("q", q, "activeOnly", activeOnly), LookupResponse.class,
                customerRepository, username, companyId, Customer.class,
                SpecificationUtils.allOf(
                        SpecificationUtils.anyContainsIgnoreCase(q, "code", "name"),
                        activeOnly ? SpecificationUtils.booleanEquals("active", true) : null),
                withDefaultSort(pageable, "name"), this::toLookup);
    }

    public LookupResponse customer(Long id, String username) {
        Customer c = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + id));
        assertCompanyAccess(username, c.getCompany().getId());
        return toLookup(c);
    }

    public Page<LookupResponse> warehouses(String username, Long companyId, String q, boolean activeOnly, Pageable pageable) {
        return search(CacheRegion.LOOKUP_WAREHOUSE, params("q", q, "activeOnly", activeOnly), LookupResponse.class,
                warehouseRepository, username, companyId, Warehouse.class,
                SpecificationUtils.allOf(
                        SpecificationUtils.anyContainsIgnoreCase(q, "code", "name"),
                        activeOnly ? SpecificationUtils.booleanEquals("active", true) : null),
                withDefaultSort(pageable, "name"), this::toLookup);
    }

    public LookupResponse warehouse(Long id, String username) {
        Warehouse w = warehouseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Warehouse not found: " + id));
        assertCompanyAccess(username, w.getCompany().getId());
        return toLookup(w);
    }

    public Page<ItemLookupResponse> items(String username, Long companyId, String q, ItemTag tag, boolean activeOnly,
                                          boolean canViewCostPrice, Pageable pageable) {
        Specification<Item> tagged = tag == null ? null : (root, query, cb) -> {
            query.distinct(true);
            return cb.equal(root.join("tags"), tag);
        };
        return search(CacheRegion.LOOKUP_ITEM,
                params("q", q, "tag", tag, "activeOnly", activeOnly, "canViewCostPrice", canViewCostPrice), ItemLookupResponse.class,
                itemRepository, username, companyId, Item.class,
                SpecificationUtils.allOf(
                        SpecificationUtils.anyContainsIgnoreCase(q, "itemCode", "name"),
                        tagged,
                        activeOnly ? SpecificationUtils.booleanEquals("active", true) : null),
                withDefaultSort(pageable, "name"), i -> toLookup(i, canViewCostPrice));
    }

    public ItemLookupResponse item(Long id, String username, boolean canViewCostPrice) {
        Item i = itemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + id));
        assertCompanyAccess(username, i.getCompany().getId());
        return toLookup(i, canViewCostPrice);
    }

    public Page<LookupResponse> itemCategories(String username, Long companyId, String q, Pageable pageable) {
        return search(CacheRegion.LOOKUP_ITEM_CATEGORY, params("q", q), LookupResponse.class,
                itemCategoryRepository, username, companyId, ItemCategory.class,
                SpecificationUtils.containsIgnoreCase("name", q),
                withDefaultSort(pageable, "name"), this::toLookup);
    }

    public LookupResponse itemCategory(Long id, String username) {
        ItemCategory c = itemCategoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Item category not found: " + id));
        assertCompanyAccess(username, c.getCompany().getId());
        return toLookup(c);
    }

    public Page<LookupResponse> itemGroups(String username, Long companyId, String q, boolean activeOnly, Pageable pageable) {
        return search(CacheRegion.LOOKUP_ITEM_GROUP, params("q", q, "activeOnly", activeOnly), LookupResponse.class,
                itemGroupRepository, username, companyId, ItemGroup.class,
                SpecificationUtils.allOf(
                        SpecificationUtils.containsIgnoreCase("name", q),
                        activeOnly ? SpecificationUtils.booleanEquals("active", true) : null),
                withDefaultSort(pageable, "name"), this::toLookup);
    }

    public LookupResponse itemGroup(Long id, String username) {
        ItemGroup g = itemGroupRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Item group not found: " + id));
        assertCompanyAccess(username, g.getCompany().getId());
        return toLookup(g);
    }

    public Page<LookupResponse> employees(String username, Long companyId, String q, boolean activeOnly, Pageable pageable) {
        return search(CacheRegion.LOOKUP_EMPLOYEE, params("q", q, "activeOnly", activeOnly), LookupResponse.class,
                employeeRepository, username, companyId, Employee.class,
                SpecificationUtils.allOf(
                        SpecificationUtils.anyContainsIgnoreCase(q, "employeeCode", "firstName", "lastName"),
                        activeOnly ? SpecificationUtils.booleanEquals("active", true) : null),
                withDefaultSort(pageable, "lastName"), this::toLookup);
    }

    public LookupResponse employee(Long id, String username) {
        Employee e = employeeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + id));
        assertCompanyAccess(username, e.getCompany().getId());
        return toLookup(e);
    }

    // Users belong to many companies, so they're scoped through the user_companies join rather
    // than a company FK: the dropdown lists users who are members of the requested company.
    public Page<LookupResponse> users(String username, Long companyId, String q, Pageable pageable) {
        assertCompanyAccess(username, requireCompany(companyId));
        Specification<User> member = (root, query, cb) -> {
            query.distinct(true);
            Join<User, Company> companies = root.join("companies");
            return cb.equal(companies.get("id"), companyId);
        };
        Specification<User> spec = SpecificationUtils.allOf(
                member,
                SpecificationUtils.anyContainsIgnoreCase(q, "username", "displayName"));
        log.debug("User '{}' looking up User (companyId={})", username, companyId);
        Pageable sorted = withDefaultSort(pageable, "displayName");
        return queryCache.page(CacheRegion.LOOKUP_USER, CacheScope.company(companyId), params("q", q), sorted,
                LookupResponse.class, () -> userRepository.findAll(spec, sorted).map(this::toLookup));
    }

    public LookupResponse user(Long id, String username) {
        User target = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        User caller = loadUser(username);
        if (!isSuperAdmin(caller)) {
            boolean sharesCompany = target.getCompanies().stream()
                    .anyMatch(tc -> caller.getCompanies().stream().anyMatch(cc -> cc.getId().equals(tc.getId())));
            if (!sharesCompany) {
                log.warn("User '{}' denied lookup of user {} (no shared company)", username, id);
                throw new SecurityException("Access denied to user: " + id);
            }
        }
        return toLookup(target);
    }

    // Roles are system-wide (not company-scoped), so no company filter applies.
    public Page<LookupResponse> roles(String q, Pageable pageable) {
        Specification<Role> spec = SpecificationUtils.anyContainsIgnoreCase(q, "name", "displayName");
        Pageable sorted = withDefaultSort(pageable, "displayName");
        return queryCache.page(CacheRegion.LOOKUP_ROLE, CacheScope.global(), params("q", q), sorted, LookupResponse.class,
                () -> roleRepository.findAll(SpecificationUtils.allOf(spec), sorted).map(this::toLookup));
    }

    public LookupResponse role(Long id) {
        return roleRepository.findById(id).map(this::toLookup)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + id));
    }

    // ---- source transactions ----

    // openOnly = not voided and not fully loaded, i.e. still a valid source for an invoice/receive.
    // The consuming service still enforces its own finer rules (e.g. a partially received PO can't
    // be invoiced) — this only trims the obvious non-candidates from the dropdown.
    public Page<TransactionLookupResponse> purchaseOrders(String username, Long companyId, String q, Long supplierId,
                                                          Long warehouseId, boolean openOnly, boolean canViewCostPrice,
                                                          Pageable pageable) {
        return search(CacheRegion.LOOKUP_PURCHASE_ORDER,
                params("q", q, "supplierId", supplierId, "warehouseId", warehouseId, "openOnly", openOnly,
                        "canViewCostPrice", canViewCostPrice), TransactionLookupResponse.class,
                purchaseOrderRepository, username, companyId, PurchaseOrder.class,
                SpecificationUtils.allOf(
                        SpecificationUtils.anyContainsIgnoreCase(q, "referenceNumber", "sheetNumber"),
                        idEquals("supplier", supplierId),
                        idEquals("warehouse", warehouseId),
                        openOnly ? SpecificationUtils.booleanEquals("voided", false) : null,
                        openOnly ? SpecificationUtils.booleanEquals("loaded", false) : null),
                withDefaultSort(pageable, Sort.by(Sort.Direction.DESC, "orderDate")), po -> toLookup(po, canViewCostPrice));
    }

    public TransactionLookupResponse purchaseOrder(Long id, String username, boolean canViewCostPrice) {
        PurchaseOrder po = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Purchase order not found: " + id));
        assertCompanyAccess(username, po.getCompany().getId());
        return toLookup(po, canViewCostPrice);
    }

    // openOnly additionally restricts to Direct-mode invoices: a PO-based invoice has nothing of its
    // own to receive (see PurchaseReceiveService), so it's never a valid Receive source.
    public Page<TransactionLookupResponse> purchaseInvoices(String username, Long companyId, String q, Long supplierId,
                                                            Long warehouseId, boolean openOnly, boolean canViewCostPrice,
                                                            Pageable pageable) {
        Specification<PurchaseInvoice> directOnly = openOnly ? (root, query, cb) -> cb.isNull(root.get("purchaseOrder")) : null;
        return search(CacheRegion.LOOKUP_PURCHASE_INVOICE,
                params("q", q, "supplierId", supplierId, "warehouseId", warehouseId, "openOnly", openOnly,
                        "canViewCostPrice", canViewCostPrice), TransactionLookupResponse.class,
                purchaseInvoiceRepository, username, companyId, PurchaseInvoice.class,
                SpecificationUtils.allOf(
                        SpecificationUtils.anyContainsIgnoreCase(q, "referenceNumber", "sheetNumber"),
                        idEquals("supplier", supplierId),
                        idEquals("warehouse", warehouseId),
                        directOnly,
                        openOnly ? SpecificationUtils.booleanEquals("voided", false) : null,
                        openOnly ? SpecificationUtils.booleanEquals("loaded", false) : null),
                withDefaultSort(pageable, Sort.by(Sort.Direction.DESC, "invoiceDate")), pi -> toLookup(pi, canViewCostPrice));
    }

    public TransactionLookupResponse purchaseInvoice(Long id, String username, boolean canViewCostPrice) {
        PurchaseInvoice pi = purchaseInvoiceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Purchase invoice not found: " + id));
        assertCompanyAccess(username, pi.getCompany().getId());
        return toLookup(pi, canViewCostPrice);
    }

    // ---- stock ----

    // Live on-hand/in-transit quantities for the given items in one warehouse, read straight from the
    // running InventoryBalance (not cached: every posted movement changes it). One row per requested
    // item — zeros when the item has never moved in that warehouse — so the form needn't special-case
    // a missing balance. The warehouse must belong to the requested company; items from elsewhere can't
    // have a balance in it, so they just come back as zeros without leaking anything.
    public List<StockLookupResponse> stock(String username, Long companyId, Long warehouseId, Collection<Long> itemIds) {
        assertCompanyAccess(username, requireCompany(companyId));
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new IllegalArgumentException("Warehouse not found: " + warehouseId));
        if (!warehouse.getCompany().getId().equals(companyId)) {
            log.warn("User '{}' denied stock lookup: warehouse {} is not in company {}", username, warehouseId, companyId);
            throw new SecurityException("Access denied to warehouse: " + warehouseId);
        }
        Set<Long> ids = Set.copyOf(itemIds);
        log.debug("User '{}' looking up stock (companyId={}, warehouseId={}, items={})", username, companyId, warehouseId, ids.size());
        Map<Long, InventoryBalance> byItem = inventoryBalanceRepository.findByWarehouseIdAndItemIdIn(warehouseId, ids).stream()
                .collect(Collectors.toMap(b -> b.getItem().getId(), Function.identity()));
        return ids.stream()
                .sorted()
                .map(itemId -> {
                    InventoryBalance b = byItem.get(itemId);
                    return new StockLookupResponse(itemId, warehouseId,
                            b != null ? b.getQuantity() : BigDecimal.ZERO,
                            b != null ? b.getTransitQuantity() : BigDecimal.ZERO);
                })
                .toList();
    }

    // ---- mapping ----

    private LookupResponse toLookup(Supplier s) {
        return new LookupResponse(s.getId(), s.getCompany().getId(), s.getCode(), s.getName(), s.isActive());
    }

    private LookupResponse toLookup(Customer c) {
        return new LookupResponse(c.getId(), c.getCompany().getId(), c.getCode(), c.getName(), c.isActive());
    }

    private LookupResponse toLookup(Warehouse w) {
        return new LookupResponse(w.getId(), w.getCompany().getId(), w.getCode(), w.getName(), w.isActive());
    }

    private ItemLookupResponse toLookup(Item i, boolean canViewCostPrice) {
        BigDecimal costPrice = !canViewCostPrice ? null : i.getPrices().stream()
                .filter(p -> p.getPriceType() == PriceType.COST_PRICE)
                .map(ItemPrice::getAmount)
                .findFirst().orElse(null);
        return new ItemLookupResponse(i.getId(), i.getCompany().getId(), i.getItemCode(), i.getName(), i.isActive(),
                Set.copyOf(i.getTags()), costPrice);
    }

    private LookupResponse toLookup(ItemCategory c) {
        return new LookupResponse(c.getId(), c.getCompany().getId(), null, c.getName(), true);
    }

    private LookupResponse toLookup(ItemGroup g) {
        return new LookupResponse(g.getId(), g.getCompany().getId(), g.getBnInitials(), g.getName(), g.isActive());
    }

    private LookupResponse toLookup(Employee e) {
        return new LookupResponse(e.getId(), e.getCompany().getId(), e.getEmployeeCode(),
                e.getFirstName() + " " + e.getLastName(), e.isActive());
    }

    private LookupResponse toLookup(User u) {
        return new LookupResponse(u.getId(), null, u.getUsername(), u.getDisplayName(), true);
    }

    private LookupResponse toLookup(Role r) {
        return new LookupResponse(r.getId(), null, r.getName(), r.getDisplayName(), true);
    }

    private TransactionLookupResponse toLookup(PurchaseOrder po, boolean canViewCostPrice) {
        List<TransactionLookupResponse.Line> lines = po.getLines().stream()
                .map(l -> new TransactionLookupResponse.Line(l.getId(), l.getLineNumber(), l.getItem().getId(),
                        l.getItem().getItemCode(), l.getItem().getName(), l.getQuantity(), l.getQuantityLoaded(),
                        canViewCostPrice ? l.getCostPrice() : null))
                .toList();
        return new TransactionLookupResponse(po.getId(), po.getCompany().getId(), po.getReferenceNumber(), po.getOrderDate(),
                po.getSupplier().getId(), po.getSupplier().getName(), po.getWarehouse().getId(), po.getWarehouse().getName(),
                null, po.isVoided(), po.isLoaded(), lines);
    }

    private TransactionLookupResponse toLookup(PurchaseInvoice pi, boolean canViewCostPrice) {
        List<TransactionLookupResponse.Line> lines = pi.getLines().stream()
                .map(l -> new TransactionLookupResponse.Line(l.getId(), l.getLineNumber(), l.getItem().getId(),
                        l.getItem().getItemCode(), l.getItem().getName(), l.getQuantity(), l.getQuantityLoaded(),
                        canViewCostPrice ? l.getCostPrice() : null))
                .toList();
        return new TransactionLookupResponse(pi.getId(), pi.getCompany().getId(), pi.getReferenceNumber(), pi.getInvoiceDate(),
                pi.getSupplier().getId(), pi.getSupplier().getName(), pi.getWarehouse().getId(), pi.getWarehouse().getName(),
                pi.getPurchaseOrder() != null ? pi.getPurchaseOrder().getId() : null,
                pi.isVoided(), pi.isLoaded(), lines);
    }

    // ---- helpers ----

    // params must hold every input that changes the result besides company and paging — including
    // permission-dependent shaping like canViewCostPrice — since it is what distinguishes cache entries.
    private <E, R> Page<R> search(CacheRegion region, Map<String, Object> params, Class<R> resultType,
                                  JpaSpecificationExecutor<E> repository, String username, Long companyId, Class<E> type,
                                  Specification<E> filters, Pageable pageable, Function<E, R> mapper) {
        // Access check first, always: the cache is keyed by company and must never be reached unchecked.
        assertCompanyAccess(username, requireCompany(companyId));
        Specification<E> companyScope = (root, query, cb) -> cb.equal(root.get("company").get("id"), companyId);

        log.debug("User '{}' looking up {} (companyId={})", username, type.getSimpleName(), companyId);
        return queryCache.page(region, CacheScope.company(companyId), params, pageable, resultType,
                () -> repository.findAll(SpecificationUtils.allOf(companyScope, filters), pageable).map(mapper));
    }


    // Every company-scoped entity belongs to exactly one company, so a dropdown never mixes
    // companies: the caller must say which one (the controller falls back to X-Company-Id).
    private static Long requireCompany(Long companyId) {
        if (companyId == null) {
            throw new IllegalArgumentException("companyId is required (query parameter or X-Company-Id header)");
        }
        return companyId;
    }

    private static <E> Specification<E> idEquals(String association, Long id) {
        if (id == null) return null;
        return (root, query, cb) -> cb.equal(root.get(association).get("id"), id);
    }

    private static Pageable withDefaultSort(Pageable pageable, String property) {
        return withDefaultSort(pageable, Sort.by(property));
    }

    private static Pageable withDefaultSort(Pageable pageable, Sort sort) {
        if (pageable.getSort().isSorted()) return pageable;
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    private User loadUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
    }

    private static boolean isSuperAdmin(User user) {
        return user.getRoles().stream().anyMatch(r -> r.getName().equals("SUPER_ADMIN"));
    }

    private void assertCompanyAccess(String username, Long companyId) {
        User user = loadUser(username);
        if (isSuperAdmin(user)) return;

        boolean hasAccess = user.getCompanies().stream()
                .anyMatch(c -> c.getId().equals(companyId));
        if (!hasAccess) {
            log.warn("User '{}' denied access to company {}", username, companyId);
            throw new SecurityException("Access denied to company: " + companyId);
        }
    }
}
