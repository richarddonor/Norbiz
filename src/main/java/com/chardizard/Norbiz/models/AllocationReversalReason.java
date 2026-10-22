package com.chardizard.Norbiz.models;

// Why an OpenItemAllocation was reversed: its document was voided, the check that paid it bounced,
// or the issued check that paid it was cancelled.
public enum AllocationReversalReason {
    VOIDED, BOUNCED, CANCELLED
}
