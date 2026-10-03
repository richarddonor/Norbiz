package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.ItemLookupResponse;
import com.chardizard.Norbiz.dto.LookupResponse;
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
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Backs the slim {@code /lookups/*} dropdown endpoints. Every list is scoped to exactly one company
 * (the form's company, defaulting to the session's X-Company-Id), access-checked against the caller's
 * memberships (SUPER_ADMIN bypasses), and every by-id read verifies company membership — opening
 * these endpoints to more permissions (see LookupAccess) must not open them across tenants.
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
    private final EmployeeRepository employeeRepository;
    private final RoleRepository roleRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseInvoiceRepository purchaseInvoiceRepository;

    // ---- master data ----

    public Page<LookupResponse> suppliers(String username, Long companyId, String q, boolean activeOnly, Pageable pageable) {
        return search(supplierRepository, username, companyId, Supplier.class,
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
        return search(customerRepository, username, companyId, Customer.class,
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
        return search(warehouseRepository, username, companyId, Warehouse.class,
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
        return search(itemRepository, username, companyId, Item.class,
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
        return search(itemCategoryRepository, username, companyId, ItemCategory.class,
                SpecificationUtils.containsIgnoreCase("name", q),
                withDefaultSort(pageable, "name"), this::toLookup);
    }

    public LookupResponse itemCategory(Long id, String username) {
        ItemCategory c = itemCategoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Item category not found: " + id));
        assertCompanyAccess(username, c.getCompany().getId());
        return toLookup(c);
    }

    public Page<LookupResponse> employees(String username, Long companyId, String q, boolean activeOnly, Pageable pageable) {
        return search(employeeRepository, username, companyId, Employee.class,
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
        return userRepository.findAll(spec, withDefaultSort(pageable, "displayName")).map(this::toLookup);
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
        return roleRepository.findAll(SpecificationUtils.allOf(spec), withDefaultSort(pageable, "displayName"))
                .map(this::toLookup);
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
        return search(purchaseOrderRepository, username, companyId, PurchaseOrder.class,
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
        return search(purchaseInvoiceRepository, username, companyId, PurchaseInvoice.class,
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

    private <E, R> Page<R> search(JpaSpecificationExecutor<E> repository, String username, Long companyId, Class<E> type,
                                  Specification<E> filters, Pageable pageable, Function<E, R> mapper) {
        assertCompanyAccess(username, requireCompany(companyId));
        Specification<E> companyScope = (root, query, cb) -> cb.equal(root.get("company").get("id"), companyId);

        log.debug("User '{}' looking up {} (companyId={})", username, type.getSimpleName(), companyId);
        return repository.findAll(SpecificationUtils.allOf(companyScope, filters), pageable).map(mapper);
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
