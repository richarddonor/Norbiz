package com.chardizard.Norbiz.models;

// How we paid out. CHECK stays outstanding (ISSUED) until it clears; the others settle immediately.
public enum IssuedPaymentMode {
    CASH, CHECK, BANK_TRANSFER, OTHERS
}
