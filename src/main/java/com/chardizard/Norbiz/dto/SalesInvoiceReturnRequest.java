package com.chardizard.Norbiz.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SalesInvoiceReturnRequest {

    @NotNull
    private Long companyId;

    // Every line must be on one of this customer's (non-voided) Sales Invoices.
    @NotNull
    private Long customerId;

    @NotBlank
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String returnDate;

    @Size(max = 255)
    private String remarks;

    // Optional control number from the physical source document, if any.
    @Size(max = 100)
    private String sheetNumber;

    // Apply the credit to the returned invoices' open balances right away (default). Whatever isn't applied stays on
    // the customer's account as unapplied credit, usable later on a Customer Payment or Refund.
    private Boolean applyToInvoices;

    @NotEmpty
    @Valid
    @Size(max = 500)
    private List<SalesInvoiceReturnLineRequest> lines;
}
