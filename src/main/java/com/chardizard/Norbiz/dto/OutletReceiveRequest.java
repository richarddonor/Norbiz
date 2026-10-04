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
public class OutletReceiveRequest {

    @NotNull
    private Long companyId;

    // Outlet and warehouse are taken from this Delivery Receipt (must be to an OUTLET customer).
    @NotNull
    private Long deliveryReceiptId;

    @NotNull
    private String receiptDate;

    @Size(max = 255)
    private String remarks;

    // Optional control number from the physical source document, if any.
    @Size(max = 100)
    private String sheetNumber;

    @NotEmpty
    @Valid
    private List<OutletReceiveLineRequest> lines;
}
