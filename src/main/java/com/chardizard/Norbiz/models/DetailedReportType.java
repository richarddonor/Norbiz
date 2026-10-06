package com.chardizard.Norbiz.models;

/**
 * Every "&lt;Transaction&gt; - Detailed" report — one per {@link TransactionType}, one row per line item
 * with its transaction's header fields. Each has its own VIEW_&lt;TYPE&gt;_DETAILED_REPORT permission,
 * seeded by DataInitializer. Adding a transaction type means adding its constant here too
 * (see docs/TRANSACTIONS.md → Detailed reports).
 */
public enum DetailedReportType {
    INVENTORY_ADJUSTMENT(TransactionType.INVENTORY_ADJUSTMENT, "Inventory Adjustment", "Inventory"),
    OUTLET_RECEIVE(TransactionType.OUTLET_RECEIVE, "Outlet Receive", "Inventory"),
    PURCHASE_ORDER(TransactionType.PURCHASE_ORDER, "Purchase Order", "Purchases"),
    PURCHASE_INVOICE(TransactionType.PURCHASE_INVOICE, "Purchase Invoice", "Purchases"),
    PURCHASE_RECEIVE(TransactionType.PURCHASE_RECEIVE, "Purchase Receive", "Purchases"),
    DELIVERY_RECEIPT(TransactionType.DELIVERY_RECEIPT, "Delivery Receipt", "Sales");

    private final TransactionType transactionType;
    private final String transactionLabel;
    private final String category;

    DetailedReportType(TransactionType transactionType, String transactionLabel, String category) {
        this.transactionType = transactionType;
        this.transactionLabel = transactionLabel;
        this.category = category;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    /** Uniform report name, e.g. "Purchase Order - Detailed". */
    public String getDisplayName() {
        return transactionLabel + " - Detailed";
    }

    /** e.g. VIEW_PURCHASE_ORDER_DETAILED_REPORT */
    public String getPermission() {
        return "VIEW_" + transactionType.name() + "_DETAILED_REPORT";
    }

    /** Permission description, e.g. "Reports - Purchase Order - Detailed". */
    public String getPermissionDescription() {
        return "Reports - " + getDisplayName();
    }

    public String getCategory() {
        return category;
    }
}
