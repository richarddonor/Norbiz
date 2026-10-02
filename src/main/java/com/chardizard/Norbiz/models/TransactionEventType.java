package com.chardizard.Norbiz.models;

// CREATED/VOIDED are recorded automatically by the transaction services;
// ACTION is a user-taken, company-configured action (see TransactionActionDefinition).
public enum TransactionEventType {
    CREATED,
    VOIDED,
    ACTION
}
