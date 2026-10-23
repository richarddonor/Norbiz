package com.chardizard.Norbiz.models;

// Every posted transaction type. Names double as the TransactionSequence key and the
// TransactionEvent.transactionType value — see docs/TRANSACTION_ACTIONS.md.
public enum TransactionType {
    INVENTORY_ADJUSTMENT("VIEW_INVENTORY_ADJUSTMENT"),
    PURCHASE_ORDER("VIEW_PURCHASE_ORDER"),
    PURCHASE_INVOICE("VIEW_PURCHASE_INVOICE"),
    PURCHASE_RECEIPT("VIEW_PURCHASE_RECEIPT"),
    DELIVERY_RECEIPT("VIEW_DELIVERY_RECEIPT"),
    OUTLET_RECEIPT("VIEW_OUTLET_RECEIPT"),
    OUTLET_DELIVERY_RECEIPT("VIEW_OUTLET_DELIVERY_RECEIPT"),
    OUTLET_DELIVERY_RETURN("VIEW_OUTLET_DELIVERY_RETURN"),
    STOCK_TRANSFER("VIEW_STOCK_TRANSFER"),
    OUTLET_PULL_OUT("VIEW_OUTLET_PULL_OUT"),
    PULL_OUT_RECEIPT("VIEW_PULL_OUT_RECEIPT"),
    ASSEMBLY("VIEW_ASSEMBLY"),
    SALES_INVOICE("VIEW_SALES_INVOICE"),
    SALES_INVOICE_RETURN("VIEW_SALES_INVOICE_RETURN"),
    // One type per memo table; DEBIT/CREDIT memos number from separate sequences (CUSTOMER_MEMO_DEBIT /
    // CUSTOMER_MEMO_CREDIT, prefixes CDM/CCM) — the one exception to "type name = sequence key".
    CUSTOMER_MEMO("VIEW_CUSTOMER_MEMO"),
    SUPPLIER_MEMO("VIEW_SUPPLIER_MEMO"),
    CUSTOMER_PAYMENT("VIEW_CUSTOMER_PAYMENT"),
    SUPPLIER_PAYMENT("VIEW_SUPPLIER_PAYMENT"),
    CUSTOMER_REFUND("VIEW_CUSTOMER_REFUND"),
    SUPPLIER_REFUND("VIEW_SUPPLIER_REFUND"),
    DEPOSIT("VIEW_DEPOSIT"),
    BANK_RECONCILIATION("VIEW_BANK_RECONCILIATION"),
    STATEMENT_OF_ACCOUNT("VIEW_STATEMENT_OF_ACCOUNT");

    private final String viewPermission;

    TransactionType(String viewPermission) {
        this.viewPermission = viewPermission;
    }

    public String getViewPermission() {
        return viewPermission;
    }
}
