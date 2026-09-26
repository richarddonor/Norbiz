package com.chardizard.Norbiz.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PurchaseReceiveRequest {

    @NotNull
    private Long companyId;

    @NotNull
    private Long warehouseId;

    @NotNull
    private Long supplierId;

    // Exactly one of purchaseOrderId / purchaseInvoiceId must be supplied — see
    // PurchaseReceiveService.create. purchaseInvoiceId must reference a Direct-mode invoice.
    private Long purchaseOrderId;

    private Long purchaseInvoiceId;

    @NotNull
    private String receiptDate;

    @Size(max = 255)
    private String remarks;

    // Optional control number from the physical source document, if any.
    @Size(max = 100)
    private String sheetNumber;

    @NotEmpty
    @Valid
    private List<PurchaseReceiveLineRequest> lines;
}
