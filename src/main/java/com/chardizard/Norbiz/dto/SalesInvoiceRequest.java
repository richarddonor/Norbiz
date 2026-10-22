package com.chardizard.Norbiz.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class SalesInvoiceRequest {

    @NotNull
    private Long companyId;

    // Required in Direct mode. When billing a Delivery Receipt it is taken from the DR (must match if supplied).
    private Long customerId;

    // Presence selects the mode: set = bill this (plain-customer) Delivery Receipt in full, lines copied from it.
    // Null = Direct mode — lines are required and the stock leaves the company's main warehouse.
    private Long deliveryReceiptId;

    @NotBlank
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String invoiceDate;

    // Payment terms in days; defaults to the customer's termDays.
    @Min(0)
    @Max(365)
    private Integer termDays;

    // yyyy-MM-dd; defaults to invoiceDate + termDays.
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String dueDate;

    @Size(max = 255)
    private String remarks;

    @Size(max = 100)
    private String sheetNumber;

    // Transaction-level discount percentage, applied on top of per-line discounts.
    @DecimalMin(value = "0")
    @DecimalMax(value = "100")
    private BigDecimal discountPercentage;

    @Valid
    @Size(max = 500)
    private List<SalesInvoiceLineRequest> lines;
}
