package com.chardizard.Norbiz.models;

// Every posted transaction type. Names double as the TransactionSequence key and the
// TransactionEvent.transactionType value — see docs/TRANSACTION_ACTIONS.md.
public enum TransactionType {
    INVENTORY_ADJUSTMENT("VIEW_INVENTORY_ADJUSTMENT"),
    PURCHASE_ORDER("VIEW_PURCHASE_ORDER"),
    PURCHASE_INVOICE("VIEW_PURCHASE_INVOICE"),
    PURCHASE_RECEIVE("VIEW_PURCHASE_RECEIVE");

    private final String viewPermission;

    TransactionType(String viewPermission) {
        this.viewPermission = viewPermission;
    }

    public String getViewPermission() {
        return viewPermission;
    }
}
