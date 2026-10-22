package com.chardizard.Norbiz.models;

// Kind of a BankEntry (signed: deposits/receipts/interest are +, disbursements/returned checks/charges are -).
public enum BankEntryType {
    DEPOSIT, TRANSFER_RECEIPT, CHECK_RETURNED, CHECK_DISBURSEMENT, TRANSFER_DISBURSEMENT, BANK_CHARGE, INTEREST, ADJUSTMENT
}
