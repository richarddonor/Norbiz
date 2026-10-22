package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.IssuedPaymentMode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

// One means of payment issued to a supplier (our check, a transfer, cash...).
@Getter
@Setter
public class IssuedTenderRequest {

    @NotNull
    private IssuedPaymentMode mode;

    @NotNull
    @DecimalMin(value = "0.01")
    @Digits(integer = 17, fraction = 2)
    private BigDecimal amount;

    // CHECK/BANK_TRANSFER: required, our account it is drawn on / sent from.
    private Long bankAccountId;

    // CHECK: required, the check number. BANK_TRANSFER: optional transaction reference.
    @Size(max = 50)
    private String checkNumber;

    // CHECK: required (yyyy-MM-dd); later than the payment date = post-dated.
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String checkDate;

    // Replaces this CANCELLED tender of the same supplier.
    private Long replacesTenderId;
}
