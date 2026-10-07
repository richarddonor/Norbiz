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
public class OutletPullOutRequest {

    @NotNull
    private Long companyId;

    // The OUTLET customer the stock is pulled from (its own warehouse is the source; the main warehouse receives it in transit).
    @NotNull
    private Long customerId;

    @NotNull
    private Long pullOutReasonId;

    @NotNull
    private String pullOutDate;

    @Size(max = 255)
    private String remarks;

    // Optional control number from the physical source document, if any.
    @Size(max = 100)
    private String sheetNumber;

    @NotEmpty
    @Valid
    private List<OutletPullOutLineRequest> lines;
}
