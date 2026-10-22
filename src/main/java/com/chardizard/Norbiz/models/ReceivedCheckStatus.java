package com.chardizard.Norbiz.models;

// Lifecycle of a received payment instrument — see docs/ACCOUNTING.md "Check lifecycles".
public enum ReceivedCheckStatus {
    ON_HAND, DEPOSITED, CLEARED, BOUNCED, REPLACED
}
