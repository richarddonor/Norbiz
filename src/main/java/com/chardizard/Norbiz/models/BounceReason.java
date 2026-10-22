package com.chardizard.Norbiz.models;

// Why a received check bounced (legacy tblCheckStatus): Drawn Against Insufficient Funds, Drawn Against Uncollected
// Deposit, stop payment, account closed, garnished account, signature/amount differs, other.
public enum BounceReason {
    DAIF, DAUD, STOP_PAYMENT, ACCOUNT_CLOSED, GARNISHED, SIGNATURE_DIFFERS, OTHER
}
