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
public class OutletDeliveryReceiptRequest {

    @NotNull
    private Long companyId;

    // The OUTLET customer making the sale. No warehouseId: stock always leaves the outlet's own warehouse.
    @NotNull
    private Long customerId;

    // Employee tagged AGENT who earns the commission.
    @NotNull
    private Long agentId;

    @NotNull
    private String deliveryDate;

    @Size(max = 255)
    private String remarks;

    // Optional control number from the physical source document, if any.
    @Size(max = 100)
    private String sheetNumber;

    @NotEmpty
    @Valid
    private List<OutletDeliveryReceiptLineRequest> lines;
}
