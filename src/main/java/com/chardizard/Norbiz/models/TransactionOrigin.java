package com.chardizard.Norbiz.models;

// Where a transaction came from. Every transaction created through the API is NATIVE; the other two
// are only ever written by the legacy (jbsKarutora) migration loader — see docs/LEGACY_MIGRATION.md.
public enum TransactionOrigin {
    NATIVE,
    // Copied 1:1 from a legacy document, keeping its legacy reference number.
    MIGRATED,
    // Didn't exist in legacy: created by the migration to complete a flow the legacy data skipped
    // (e.g. a Pull Out Receive for a pull out that went straight to on-hand). Remarks say why.
    RECONSTRUCTED
}
