package com.chardizard.Norbiz.models;

// OPEN while balance > 0, CLOSED once fully applied, VOIDED when its source document was voided (or its check bounced).
public enum OpenItemStatus {
    OPEN, CLOSED, VOIDED
}
