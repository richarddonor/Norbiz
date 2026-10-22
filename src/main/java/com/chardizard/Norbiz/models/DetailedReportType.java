package com.chardizard.Norbiz.models;

/**
 * Every "&lt;Transaction&gt; - Detailed" report — one per {@link TransactionType}, one row per line item
 * with its transaction's header fields (for accounting documents: per memo line / tender / application / refund
 * line / deposit line / reconciliation line / statement line, with no item fields — see {@link #isItemized()}). Each has its own VIEW_&lt;TYPE&gt;_DETAILED_REPORT permission,
 * seeded by DataInitializer. Adding a transaction type means adding its constant here too
 * (see docs/TRANSACTIONS.md → Detailed reports).
 */
public enum DetailedReportType {
    INVENTORY_ADJUSTMENT(TransactionType.INVENTORY_ADJUSTMENT, "Inventory Adjustment", "Inventory"),
    OUTLET_RECEIPT(TransactionType.OUTLET_RECEIPT, "Outlet Receipt", "Inventory"),
    PURCHASE_ORDER(TransactionType.PURCHASE_ORDER, "Purchase Order", "Purchases"),
    PURCHASE_INVOICE(TransactionType.PURCHASE_INVOICE, "Purchase Invoice", "Purchases"),
    PURCHASE_RECEIPT(TransactionType.PURCHASE_RECEIPT, "Purchase Receipt", "Purchases"),
    DELIVERY_RECEIPT(TransactionType.DELIVERY_RECEIPT, "Delivery Receipt", "Sales"),
    OUTLET_DELIVERY_RECEIPT(TransactionType.OUTLET_DELIVERY_RECEIPT, "Outlet Delivery Receipt", "Sales"),
    OUTLET_DELIVERY_RETURN(TransactionType.OUTLET_DELIVERY_RETURN, "Outlet Delivery Return", "Sales"),
    STOCK_TRANSFER(TransactionType.STOCK_TRANSFER, "Stock Transfer", "Sales"),
    OUTLET_PULL_OUT(TransactionType.OUTLET_PULL_OUT, "Outlet Pull-out", "Sales"),
    PULL_OUT_RECEIPT(TransactionType.PULL_OUT_RECEIPT, "Pull-out Receipt", "Sales"),
    ASSEMBLY(TransactionType.ASSEMBLY, "Assembly", "Inventory"),
    SALES_INVOICE(TransactionType.SALES_INVOICE, "Sales Invoice", "Sales"),
    CUSTOMER_MEMO(TransactionType.CUSTOMER_MEMO, "Customer Memo", "Accounting"),
    SUPPLIER_MEMO(TransactionType.SUPPLIER_MEMO, "Supplier Memo", "Accounting"),
    CUSTOMER_PAYMENT(TransactionType.CUSTOMER_PAYMENT, "Customer Payment", "Accounting"),
    SUPPLIER_PAYMENT(TransactionType.SUPPLIER_PAYMENT, "Supplier Payment", "Accounting"),
    CUSTOMER_CREDIT_APPLICATION(TransactionType.CUSTOMER_CREDIT_APPLICATION, "Customer Credit Application", "Accounting"),
    SUPPLIER_CREDIT_APPLICATION(TransactionType.SUPPLIER_CREDIT_APPLICATION, "Supplier Credit Application", "Accounting"),
    CUSTOMER_REFUND(TransactionType.CUSTOMER_REFUND, "Customer Refund", "Accounting"),
    SUPPLIER_REFUND(TransactionType.SUPPLIER_REFUND, "Supplier Refund", "Accounting"),
    DEPOSIT(TransactionType.DEPOSIT, "Deposit", "Accounting"),
    BANK_RECONCILIATION(TransactionType.BANK_RECONCILIATION, "Bank Reconciliation", "Accounting"),
    STATEMENT_OF_ACCOUNT(TransactionType.STATEMENT_OF_ACCOUNT, "Statement of Account", "Accounting");

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

    /** Whether its rows are item lines (with warehouse/item filters); false for the accounting documents. */
    public boolean isItemized() {
        return !"Accounting".equals(category);
    }
}
