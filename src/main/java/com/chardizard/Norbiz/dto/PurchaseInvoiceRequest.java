package com.chardizard.Norbiz.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class PurchaseInvoiceRequest {

    @NotNull
    private Long companyId;

    @NotNull
    private Long warehouseId;

    @NotNull
    private Long supplierId;

    // Presence selects the mode: set = invoice against this Purchase Order (loaded in full, 1:1).
    // Null = "Direct" mode — no backing PO, this invoice posts its own Transit Quantity movement.
    private Long purchaseOrderId;

    @NotNull
    private String invoiceDate;

    @Size(max = 255)
    private String remarks;

    // Optional control number from the physical source document, if any.
    @Size(max = 100)
    private String sheetNumber;

    // Transaction-level discount percentage, applied on top of per-line discounts.
    @DecimalMin(value = "0")
    @DecimalMax(value = "100")
    private BigDecimal discountPercentage;

    @Valid
    private List<PurchaseInvoiceFeeRequest> fees;

    // Direct mode: required, one entry per item being invoiced. PO-based mode: optional — only
    // used to supply a per-item discountPercentage override, matched by itemId (quantity/costPrice
    // are always taken from the PO). See PurchaseInvoiceService.create.
    @Valid
    private List<PurchaseInvoiceLineRequest> lines;
}
