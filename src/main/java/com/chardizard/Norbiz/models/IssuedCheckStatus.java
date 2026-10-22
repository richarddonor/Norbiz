package com.chardizard.Norbiz.models;

// Lifecycle of an issued payment instrument (our check to a supplier / refund to a customer).
public enum IssuedCheckStatus {
    ISSUED, CLEARED, CANCELLED
}
