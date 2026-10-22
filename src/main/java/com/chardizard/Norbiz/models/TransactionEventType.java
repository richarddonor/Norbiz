package com.chardizard.Norbiz.models;

// CREATED/VOIDED are recorded automatically by the transaction services; STATUS_CHANGED records a payment
// instrument's status change (check deposited/cleared/bounced/replaced/cancelled) on its payment's history;
// ACTION is a user-taken, company-configured action (see TransactionActionDefinition).
public enum TransactionEventType {
    CREATED,
    VOIDED,
    STATUS_CHANGED,
    ACTION
}
