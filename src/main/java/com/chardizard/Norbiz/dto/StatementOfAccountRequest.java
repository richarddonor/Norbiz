package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StatementOfAccountRequest {

    @NotNull
    private Long companyId;

    @NotNull
    private Long customerId;

    // As-of date of the statement (yyyy-MM-dd).
    @NotBlank
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String statementDate;

    @Size(max = 100)
    private String sheetNumber;

    @Size(max = 255)
    private String remarks;
}
