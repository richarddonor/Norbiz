package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class SalesInvoiceLineRequest {

    @NotNull
    private Long itemId;

    // Required (and used) only in Direct mode. Ignored when billing a Delivery Receipt (copied from it).
    @DecimalMin(value = "0.0001")
    @Digits(integer = 15, fraction = 4)
    private BigDecimal quantity;

    // Direct mode only — preloaded from the item's UNIT_PRICE when omitted. Ignored when billing a DR.
    @DecimalMin(value = "0")
    @Digits(integer = 15, fraction = 4)
    private BigDecimal unitPrice;

    // Per-item discount percentage — used in both modes (matched by itemId when billing a DR).
    @DecimalMin(value = "0")
    @DecimalMax(value = "100")
    private BigDecimal discountPercentage;
}
