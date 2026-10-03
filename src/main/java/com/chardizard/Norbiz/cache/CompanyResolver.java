package com.chardizard.Norbiz.cache;

import com.chardizard.Norbiz.models.*;
import org.hibernate.Hibernate;

import java.util.Set;

/**
 * Maps a written entity to the generation counter it bumps: {@code (entityName, companyId)}.
 * Most entities carry a company FK; lines and other children resolve through their parent;
 * system-wide or multi-company entities (User, Role, Permission) only have a global counter.
 *
 * A new company-scoped entity that has no {@code getCompany()} must be added here, or writes to it
 * won't invalidate the regions that depend on it.
 */
final class CompanyResolver {

    /** Entities whose writes never affect a cached response — skipped to avoid noise. */
    private static final Set<Class<?>> IGNORED = Set.of(AuditLog.class, TransactionSequence.class);

    /** Entities with no single owning company: only their {@code :all} counter exists. */
    static final Set<String> GLOBAL_ONLY = Set.of(
            User.class.getSimpleName(), Role.class.getSimpleName(), Permission.class.getSimpleName());

    /** @param companyId null when the entity is global-only */
    record Target(String entity, Long companyId) {
    }

    private CompanyResolver() {
    }

    /** @return the counter to bump, or null if this entity never affects a cached response */
    static Target resolve(Object entity) {
        Class<?> type = Hibernate.getClass(entity);
        if (IGNORED.contains(type)) return null;
        String name = type.getSimpleName();
        if (GLOBAL_ONLY.contains(name)) return new Target(name, null);
        return new Target(name, companyIdOf(entity));
    }

    private static Long companyIdOf(Object entity) {
        return switch (entity) {
            case Company c -> c.getId();
            case Brand b -> b.getCompany().getId();
            case Customer c -> c.getCompany().getId();
            case DocumentTemplate d -> d.getCompany().getId();
            case Employee e -> e.getCompany().getId();
            case InventoryAdjustment a -> a.getCompany().getId();
            case InventoryMovement m -> m.getCompany().getId();
            case Item i -> i.getCompany().getId();
            case ItemCategory c -> c.getCompany().getId();
            case ItemGroup g -> g.getCompany().getId();
            case PurchaseInvoice p -> p.getCompany().getId();
            case PurchaseOrder p -> p.getCompany().getId();
            case PurchaseReceive p -> p.getCompany().getId();
            case Supplier s -> s.getCompany().getId();
            case TransactionActionDefinition t -> t.getCompany().getId();
            case TransactionEvent t -> t.getCompany().getId();
            case Warehouse w -> w.getCompany().getId();
            // children: resolve through the parent
            case InventoryBalance b -> b.getWarehouse().getCompany().getId();
            case InventoryAdjustmentLine l -> l.getAdjustment().getCompany().getId();
            case ItemPrice p -> p.getItem().getCompany().getId();
            case ItemSku s -> s.getItem().getCompany().getId();
            case PurchaseInvoiceFee f -> f.getPurchaseInvoice().getCompany().getId();
            case PurchaseInvoiceLine l -> l.getPurchaseInvoice().getCompany().getId();
            case PurchaseOrderLine l -> l.getPurchaseOrder().getCompany().getId();
            case PurchaseReceiveLine l -> l.getPurchaseReceive().getCompany().getId();
            default -> throw new IllegalStateException("No company mapping for entity " + entity.getClass().getSimpleName()
                    + " — add it to CompanyResolver");
        };
    }
}
