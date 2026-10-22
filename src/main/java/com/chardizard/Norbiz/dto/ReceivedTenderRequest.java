package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.ReceivedPaymentMode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// One means of payment received (cash, a check, a transfer...).
@Getter
@Setter
public class ReceivedTenderRequest {

    @NotNull
    private ReceivedPaymentMode mode;

    @NotNull
    @DecimalMin(value = "0.01")
    @Digits(integer = 17, fraction = 2)
    private BigDecimal amount;

    // CHECK: required, the check number. BANK_TRANSFER/CREDIT_CARD: optional transaction reference.
    @Size(max = 50)
    private String checkNumber;

    // CHECK: required (yyyy-MM-dd); later than the payment date = post-dated.
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String checkDate;

    // CHECK: required, the drawee bank.
    private Long bankId;

    // BANK_TRANSFER/CREDIT_CARD: required, our account the money landed in. Must be omitted for CASH/CHECK (set by Deposit).
    private Long depositBankAccountId;

    @Size(max = 50)
    private String officialReceiptNumber;

    @Size(max = 50)
    private String provisionalReceiptNumber;

    // Replaces this bounced tender of the same customer (legacy replacement check).
    private Long replacesTenderId;
}
