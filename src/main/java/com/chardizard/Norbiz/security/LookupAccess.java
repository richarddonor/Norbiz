package com.chardizard.Norbiz.security;

import com.chardizard.Norbiz.models.DetailedReportType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Decides who may call the slim {@code /lookups/*} dropdown endpoints. A caller qualifies for a
 * lookup if they hold the entity's own VIEW_ permission OR any permission whose form needs that
 * entity as a dropdown (e.g. CREATE_PURCHASE_ORDER needs suppliers). This is the single place to
 * update when a new transaction type or form starts referencing an entity.
 *
 * Used from {@code @PreAuthorize("@lookupAccess.can(authentication, 'SUPPLIER')")}.
 */
@Component("lookupAccess")
public class LookupAccess {

    public enum LookupType {
        SUPPLIER, CUSTOMER, WAREHOUSE, ITEM, ITEM_CATEGORY, ITEM_GROUP, EMPLOYEE, USER, ROLE,
        PURCHASE_ORDER, PURCHASE_INVOICE, DELIVERY_RECEIPT, STOCK
    }

    private static final Map<LookupType, Set<String>> ALLOWED = new EnumMap<>(LookupType.class);

    static {
        ALLOWED.put(LookupType.SUPPLIER, Set.of(
                "VIEW_SUPPLIER",
                "CREATE_PURCHASE_ORDER", "CREATE_PURCHASE_INVOICE", "CREATE_PURCHASE_RECEIVE"));
        ALLOWED.put(LookupType.CUSTOMER, Set.of(
                "VIEW_CUSTOMER",
                "CREATE_DELIVERY_RECEIPT", "CREATE_OUTLET_RECEIVE"));
        ALLOWED.put(LookupType.WAREHOUSE, Set.of(
                "VIEW_WAREHOUSE",
                "CREATE_INVENTORY_ADJUSTMENT",
                "CREATE_PURCHASE_ORDER", "CREATE_PURCHASE_INVOICE", "CREATE_PURCHASE_RECEIVE",
                "CREATE_DELIVERY_RECEIPT",
                "VIEW_INVENTORY_REPORT"));
        ALLOWED.put(LookupType.ITEM, Set.of(
                "VIEW_ITEM",
                "CREATE_ITEM", "UPDATE_ITEM",
                "CREATE_INVENTORY_ADJUSTMENT",
                "CREATE_PURCHASE_ORDER", "CREATE_PURCHASE_INVOICE", "CREATE_PURCHASE_RECEIVE",
                "CREATE_DELIVERY_RECEIPT", "CREATE_OUTLET_RECEIVE",
                "VIEW_INVENTORY_REPORT"));
        ALLOWED.put(LookupType.ITEM_CATEGORY, Set.of(
                "VIEW_ITEM_CATEGORY",
                "CREATE_ITEM", "UPDATE_ITEM"));
        ALLOWED.put(LookupType.ITEM_GROUP, Set.of(
                "VIEW_ITEM_GROUP",
                "CREATE_ITEM", "UPDATE_ITEM"));
        ALLOWED.put(LookupType.EMPLOYEE, Set.of(
                "VIEW_EMPLOYEE"));
        ALLOWED.put(LookupType.USER, Set.of(
                "VIEW_USER",
                "CREATE_EMPLOYEE", "UPDATE_EMPLOYEE"));
        ALLOWED.put(LookupType.ROLE, Set.of(
                "VIEW_ROLE",
                "CREATE_USER", "UPDATE_USER",
                "MANAGE_TRANSACTION_ACTIONS"));
        ALLOWED.put(LookupType.PURCHASE_ORDER, Set.of(
                "VIEW_PURCHASE_ORDER",
                "CREATE_PURCHASE_INVOICE", "CREATE_PURCHASE_RECEIVE"));
        ALLOWED.put(LookupType.PURCHASE_INVOICE, Set.of(
                "VIEW_PURCHASE_INVOICE",
                "CREATE_PURCHASE_RECEIVE"));
        ALLOWED.put(LookupType.DELIVERY_RECEIPT, Set.of(
                "VIEW_DELIVERY_RECEIPT",
                "CREATE_OUTLET_RECEIVE"));
        // Current on-hand / in-transit quantity shown beside lines while creating an inventory transaction.
        ALLOWED.put(LookupType.STOCK, Set.of(
                "VIEW_INVENTORY_REPORT",
                "CREATE_INVENTORY_ADJUSTMENT",
                "CREATE_PURCHASE_ORDER", "CREATE_PURCHASE_INVOICE", "CREATE_PURCHASE_RECEIVE",
                "CREATE_DELIVERY_RECEIPT", "CREATE_OUTLET_RECEIVE"));
    }

    static {
        // Every "<Transaction> - Detailed" report filters by warehouse and item, plus its counterparty.
        for (DetailedReportType report : DetailedReportType.values()) {
            widen(LookupType.WAREHOUSE, report.getPermission());
            widen(LookupType.ITEM, report.getPermission());
            switch (report) {
                case PURCHASE_ORDER, PURCHASE_INVOICE, PURCHASE_RECEIVE -> widen(LookupType.SUPPLIER, report.getPermission());
                case DELIVERY_RECEIPT, OUTLET_RECEIVE -> widen(LookupType.CUSTOMER, report.getPermission());
                case INVENTORY_ADJUSTMENT -> { }
            }
        }
    }

    private static void widen(LookupType type, String permission) {
        Set<String> allowed = new HashSet<>(ALLOWED.get(type));
        allowed.add(permission);
        ALLOWED.put(type, Set.copyOf(allowed));
    }

    public boolean can(Authentication authentication, String lookupType) {
        if (authentication == null || !authentication.isAuthenticated()) return false;
        Set<String> allowed = ALLOWED.get(LookupType.valueOf(lookupType));
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(allowed::contains);
    }

    /** Exposed for tests and docs — the permissions that unlock a given lookup. */
    public static Set<String> allowedPermissions(LookupType type) {
        return ALLOWED.get(type);
    }
}
