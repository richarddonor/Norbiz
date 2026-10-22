package com.chardizard.Norbiz.models;

// CHARGE raises the party's balance (invoice, debit memo, refund paid out); CREDIT lowers it (payment tender, credit memo).
// Allocations always pair one CREDIT item with one CHARGE item of the same party — see docs/ACCOUNTING.md.
public enum OpenItemDirection {
    CHARGE, CREDIT
}
