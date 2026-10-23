package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class SalesInvoiceReturnLineRequest {

    // The invoice line being returned (from GET /lookups/sales-invoices).
    @NotNull
    private Long salesInvoiceLineId;

    // Amount being returned now — must not exceed what is still unreturned on that line.
    @NotNull
    @DecimalMin(value = "0.0001")
    @Digits(integer = 15, fraction = 4)
    private BigDecimal quantity;
}
