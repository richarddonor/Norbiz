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
        PURCHASE_ORDER, PURCHASE_INVOICE, DELIVERY_RECEIPT, OUTLET_DELIVERY_RECEIPT, STOCK,
        STOCK_TRANSFER, OUTLET_PULL_OUT, PULL_OUT_REASON, BILL_OF_MATERIAL, SALES_INVOICE,
        BANK, BANK_ACCOUNT, OPEN_ITEM
    }

    private static final Map<LookupType, Set<String>> ALLOWED = new EnumMap<>(LookupType.class);

    static {
        ALLOWED.put(LookupType.SUPPLIER, Set.of(
                "VIEW_SUPPLIER",
                "CREATE_PURCHASE_ORDER", "CREATE_PURCHASE_INVOICE", "CREATE_PURCHASE_RECEIPT",
                "CREATE_SUPPLIER_MEMO", "CREATE_SUPPLIER_PAYMENT", "CREATE_SUPPLIER_REFUND",
                // supplier filter on the AP list pages and reports
                "VIEW_SUPPLIER_MEMO", "VIEW_SUPPLIER_PAYMENT", "VIEW_SUPPLIER_REFUND", "VIEW_AP_REPORT"));
        ALLOWED.put(LookupType.CUSTOMER, Set.of(
                "VIEW_CUSTOMER",
                "CREATE_DELIVERY_RECEIPT", "CREATE_OUTLET_RECEIPT",
                "CREATE_OUTLET_DELIVERY_RECEIPT", "CREATE_OUTLET_DELIVERY_RETURN",
                "CREATE_STOCK_TRANSFER", "CREATE_OUTLET_PULL_OUT", "CREATE_PULL_OUT_RECEIPT",
                "CREATE_SALES_INVOICE", "CREATE_SALES_INVOICE_RETURN", "CREATE_CUSTOMER_MEMO", "CREATE_CUSTOMER_PAYMENT",
                "CREATE_CUSTOMER_REFUND", "CREATE_STATEMENT_OF_ACCOUNT",
                // outlet filter on the outlet-sales list pages; customer filter on the AR list pages and reports
                "VIEW_OUTLET_DELIVERY_RECEIPT", "VIEW_OUTLET_DELIVERY_RETURN",
                "VIEW_SALES_INVOICE", "VIEW_SALES_INVOICE_RETURN", "VIEW_CUSTOMER_MEMO", "VIEW_CUSTOMER_PAYMENT", "VIEW_CUSTOMER_REFUND",
                "VIEW_STATEMENT_OF_ACCOUNT", "VIEW_AR_REPORT"));
        ALLOWED.put(LookupType.WAREHOUSE, Set.of(
                "VIEW_WAREHOUSE",
                "CREATE_INVENTORY_ADJUSTMENT",
                "CREATE_PURCHASE_ORDER", "CREATE_PURCHASE_INVOICE", "CREATE_PURCHASE_RECEIPT",
                "CREATE_DELIVERY_RECEIPT", "CREATE_STOCK_TRANSFER", "CREATE_OUTLET_PULL_OUT", "CREATE_ASSEMBLY",
                "VIEW_INVENTORY_REPORT"));
        ALLOWED.put(LookupType.ITEM, Set.of(
                "VIEW_ITEM",
                "CREATE_ITEM", "UPDATE_ITEM",
                "CREATE_INVENTORY_ADJUSTMENT",
                "CREATE_PURCHASE_ORDER", "CREATE_PURCHASE_INVOICE", "CREATE_PURCHASE_RECEIPT",
                "CREATE_DELIVERY_RECEIPT", "CREATE_OUTLET_RECEIPT",
                "CREATE_OUTLET_DELIVERY_RECEIPT", "CREATE_OUTLET_DELIVERY_RETURN",
                "CREATE_STOCK_TRANSFER", "CREATE_OUTLET_PULL_OUT", "CREATE_PULL_OUT_RECEIPT", "CREATE_ASSEMBLY",
                "CREATE_BILL_OF_MATERIAL", "UPDATE_BILL_OF_MATERIAL", "CREATE_SALES_INVOICE",
                "VIEW_INVENTORY_REPORT"));
        ALLOWED.put(LookupType.ITEM_CATEGORY, Set.of(
                "VIEW_ITEM_CATEGORY",
                "CREATE_ITEM", "UPDATE_ITEM"));
        ALLOWED.put(LookupType.ITEM_GROUP, Set.of(
                "VIEW_ITEM_GROUP",
                "CREATE_ITEM", "UPDATE_ITEM"));
        ALLOWED.put(LookupType.EMPLOYEE, Set.of(
                "VIEW_EMPLOYEE",
                "CREATE_OUTLET_DELIVERY_RECEIPT",
                // agent filter on the outlet-sales list pages
                "VIEW_OUTLET_DELIVERY_RECEIPT", "VIEW_OUTLET_DELIVERY_RETURN"));
        ALLOWED.put(LookupType.USER, Set.of(
                "VIEW_USER",
                "CREATE_EMPLOYEE", "UPDATE_EMPLOYEE"));
        ALLOWED.put(LookupType.ROLE, Set.of(
                "VIEW_ROLE",
                "CREATE_USER", "UPDATE_USER",
                "MANAGE_TRANSACTION_ACTIONS"));
        ALLOWED.put(LookupType.PURCHASE_ORDER, Set.of(
                "VIEW_PURCHASE_ORDER",
                "CREATE_PURCHASE_INVOICE", "CREATE_PURCHASE_RECEIPT"));
        ALLOWED.put(LookupType.PURCHASE_INVOICE, Set.of(
                "VIEW_PURCHASE_INVOICE",
                "CREATE_PURCHASE_RECEIPT"));
        ALLOWED.put(LookupType.STOCK_TRANSFER, Set.of(
                "VIEW_STOCK_TRANSFER",
                "CREATE_DELIVERY_RECEIPT"));
        ALLOWED.put(LookupType.OUTLET_PULL_OUT, Set.of(
                "VIEW_OUTLET_PULL_OUT",
                "CREATE_PULL_OUT_RECEIPT"));
        ALLOWED.put(LookupType.PULL_OUT_REASON, Set.of(
                "VIEW_PULL_OUT_REASON",
                "CREATE_OUTLET_PULL_OUT",
                // reason filter on the Outlet Pull-out list page
                "VIEW_OUTLET_PULL_OUT"));
        ALLOWED.put(LookupType.BILL_OF_MATERIAL, Set.of(
                "VIEW_BILL_OF_MATERIAL",
                "CREATE_ASSEMBLY"));
        ALLOWED.put(LookupType.DELIVERY_RECEIPT, Set.of(
                "VIEW_DELIVERY_RECEIPT",
                "CREATE_OUTLET_RECEIPT", "CREATE_SALES_INVOICE"));
        ALLOWED.put(LookupType.BANK, Set.of(
                "VIEW_BANK",
                "CREATE_BANK_ACCOUNT", "UPDATE_BANK_ACCOUNT",
                "CREATE_CUSTOMER_PAYMENT", "CREATE_SUPPLIER_REFUND"));
        ALLOWED.put(LookupType.BANK_ACCOUNT, Set.of(
                "VIEW_BANK_ACCOUNT",
                "CREATE_CUSTOMER_PAYMENT", "CREATE_SUPPLIER_PAYMENT", "CREATE_CUSTOMER_REFUND", "CREATE_SUPPLIER_REFUND",
                "CREATE_DEPOSIT", "CREATE_BANK_RECONCILIATION",
                // bank account filter on the deposit / reconciliation / tender lists and the bank book
                "VIEW_DEPOSIT", "VIEW_BANK_RECONCILIATION", "VIEW_CUSTOMER_PAYMENT", "VIEW_SUPPLIER_PAYMENT", "VIEW_BANK_BOOK"));
        // A party's open receivables/payables/credits, picked by every form that applies or refunds them.
        ALLOWED.put(LookupType.OPEN_ITEM, Set.of(
                "VIEW_AR_REPORT", "VIEW_AP_REPORT",
                "CREATE_CUSTOMER_MEMO", "CREATE_SUPPLIER_MEMO", "CREATE_CUSTOMER_PAYMENT", "CREATE_SUPPLIER_PAYMENT",
                "CREATE_CUSTOMER_REFUND", "CREATE_SUPPLIER_REFUND"));
        // Source invoices (and their returnable lines) for the Sales Invoice Return form.
        ALLOWED.put(LookupType.SALES_INVOICE, Set.of(
                "VIEW_SALES_INVOICE",
                "CREATE_SALES_INVOICE_RETURN"));
        ALLOWED.put(LookupType.OUTLET_DELIVERY_RECEIPT, Set.of(
                "VIEW_OUTLET_DELIVERY_RECEIPT",
                "CREATE_OUTLET_DELIVERY_RETURN"));
        // Current on-hand / in-transit quantity shown beside lines while creating an inventory transaction.
        ALLOWED.put(LookupType.STOCK, Set.of(
                "VIEW_INVENTORY_REPORT",
                "CREATE_INVENTORY_ADJUSTMENT",
                "CREATE_PURCHASE_ORDER", "CREATE_PURCHASE_INVOICE", "CREATE_PURCHASE_RECEIPT",
                "CREATE_DELIVERY_RECEIPT", "CREATE_OUTLET_RECEIPT",
                "CREATE_OUTLET_DELIVERY_RECEIPT", "CREATE_OUTLET_DELIVERY_RETURN",
                "CREATE_STOCK_TRANSFER", "CREATE_OUTLET_PULL_OUT", "CREATE_PULL_OUT_RECEIPT", "CREATE_ASSEMBLY",
                "CREATE_SALES_INVOICE"));
    }

    static {
        // Every itemized "<Transaction> - Detailed" report filters by warehouse and item; every report by its counterparty.
        for (DetailedReportType report : DetailedReportType.values()) {
            if (report.isItemized()) {
                widen(LookupType.WAREHOUSE, report.getPermission());
                widen(LookupType.ITEM, report.getPermission());
            }
            switch (report) {
                case PURCHASE_ORDER, PURCHASE_INVOICE, PURCHASE_RECEIPT,
                     SUPPLIER_MEMO, SUPPLIER_PAYMENT, SUPPLIER_REFUND -> widen(LookupType.SUPPLIER, report.getPermission());
                case DELIVERY_RECEIPT, OUTLET_RECEIPT, STOCK_TRANSFER, OUTLET_PULL_OUT, PULL_OUT_RECEIPT, SALES_INVOICE, SALES_INVOICE_RETURN,
                     CUSTOMER_MEMO, CUSTOMER_PAYMENT, CUSTOMER_REFUND, STATEMENT_OF_ACCOUNT ->
                        widen(LookupType.CUSTOMER, report.getPermission());
                case OUTLET_DELIVERY_RECEIPT, OUTLET_DELIVERY_RETURN -> {
                    widen(LookupType.CUSTOMER, report.getPermission());
                    widen(LookupType.EMPLOYEE, report.getPermission());
                }
                case INVENTORY_ADJUSTMENT, ASSEMBLY, DEPOSIT, BANK_RECONCILIATION -> { }
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
