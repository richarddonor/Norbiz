package com.chardizard.Norbiz.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DeliveryReceiptRequest {

    @NotNull
    private Long companyId;

    // No warehouseId: stock always leaves the company's main warehouse; an OUTLET customer's own
    // warehouse receives the in-transit posting (see DeliveryReceiptService).
    @NotNull
    private Long customerId;

    @NotNull
    private String deliveryDate;

    @Size(max = 255)
    private String remarks;

    // Optional control number from the physical source document, if any.
    @Size(max = 100)
    private String sheetNumber;

    // Optional: deliver a Stock Transfer in full. Its customer must match customerId, and its lines are copied
    // verbatim — lines must then be omitted. Without it, lines are required (checked in DeliveryReceiptService).
    private Long stockTransferId;

    @Valid
    private List<DeliveryReceiptLineRequest> lines;
}
