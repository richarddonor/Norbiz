package com.chardizard.Norbiz.models;

// CLEARED_ENTRY snapshots a BankEntry the reconciliation cleared; ADJUSTMENT is a bank charge/interest/adjustment it booked.
public enum BankReconciliationLineKind {
    CLEARED_ENTRY, ADJUSTMENT
}
