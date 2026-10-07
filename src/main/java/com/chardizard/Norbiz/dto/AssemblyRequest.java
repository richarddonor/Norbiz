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
public class AssemblyRequest {

    @NotNull
    private Long companyId;

    // No warehouseId: assemblies are built in the company's main warehouse.
    @NotNull
    private String assemblyDate;

    @Size(max = 255)
    private String remarks;

    // Optional control number from the physical source document, if any.
    @Size(max = 100)
    private String sheetNumber;

    // Finished items produced (added to on-hand).
    @NotEmpty
    @Valid
    private List<AssemblyOutputRequest> outputs;

    // Raw materials consumed (deducted from on-hand).
    @NotEmpty
    @Valid
    private List<AssemblyMaterialRequest> materials;
}
