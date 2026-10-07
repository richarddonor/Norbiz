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
public class StockTransferRequest {

    @NotNull
    private Long companyId;

    // No warehouseId: stock is always set aside in the company's main warehouse.
    @NotNull
    private Long customerId;

    @NotNull
    private String transferDate;

    @Size(max = 255)
    private String remarks;

    // Optional control number from the physical source document, if any.
    @Size(max = 100)
    private String sheetNumber;

    @NotEmpty
    @Valid
    private List<StockTransferLineRequest> lines;
}
