package com.chardizard.Norbiz.models;

// How money reached us. CASH and CHECK sit on hand until deposited; BANK_TRANSFER and CREDIT_CARD land straight
// in a bank account; OTHERS is treated as settled immediately with no bank effect.
public enum ReceivedPaymentMode {
    CASH, CHECK, BANK_TRANSFER, CREDIT_CARD, OTHERS
}
