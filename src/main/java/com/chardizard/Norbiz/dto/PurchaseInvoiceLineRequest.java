package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PurchaseInvoiceLineRequest {

    @NotNull
    private Long itemId;

    // Required (and used) only in Direct mode — see PurchaseInvoiceService.create. Ignored in
    // PO-based mode, where quantity is always taken verbatim from the originating PO line.
    @DecimalMin(value = "0.0001")
    private BigDecimal quantity;

    // Optional — Direct mode only, preloaded from the item's current cost price when omitted.
    // Ignored (forced to 0) for callers without VIEW_COST_PRICE. Ignored in PO-based mode.
    private BigDecimal costPrice;

    // Per-item discount percentage — used in both modes (matched by itemId in PO-based mode).
    @DecimalMin(value = "0")
    @DecimalMax(value = "100")
    private BigDecimal discountPercentage;
}
