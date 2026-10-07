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
public class PullOutReceiveRequest {

    @NotNull
    private Long companyId;

    // Outlet and warehouse (the main warehouse it was pulled into) are taken from this Outlet Pull Out.
    @NotNull
    private Long outletPullOutId;

    @NotNull
    private String receiptDate;

    @Size(max = 255)
    private String remarks;

    // Optional control number from the physical source document, if any.
    @Size(max = 100)
    private String sheetNumber;

    @NotEmpty
    @Valid
    private List<PullOutReceiveLineRequest> lines;
}
