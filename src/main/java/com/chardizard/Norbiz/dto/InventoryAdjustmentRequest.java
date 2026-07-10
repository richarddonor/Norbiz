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
public class InventoryAdjustmentRequest {

    @NotNull
    private Long companyId;

    @NotNull
    private Long warehouseId;

    @NotNull
    private String adjustmentDate;

    @Size(max = 255)
    private String reason;

    // Optional control number from the physical source document, if any.
    @Size(max = 100)
    private String sheetNumber;

    @NotEmpty
    @Valid
    private List<InventoryAdjustmentLineRequest> lines;
}