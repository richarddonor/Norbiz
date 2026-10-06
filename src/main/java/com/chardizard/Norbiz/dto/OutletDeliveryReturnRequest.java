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
public class OutletDeliveryReturnRequest {

    @NotNull
    private Long companyId;

    // Outlet, warehouse and agent are taken from this Outlet Delivery Receipt.
    @NotNull
    private Long outletDeliveryReceiptId;

    @NotNull
    private String returnDate;

    @Size(max = 255)
    private String remarks;

    // Optional control number from the physical source document, if any.
    @Size(max = 100)
    private String sheetNumber;

    @NotEmpty
    @Valid
    private List<OutletDeliveryReturnLineRequest> lines;
}
