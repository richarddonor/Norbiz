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
public class SupplierPaymentRequest {

    @NotNull
    private Long companyId;

    @NotNull
    private Long supplierId;

    @NotBlank
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String paymentDate;

    // Disbursement voucher number, if any.
    @Size(max = 50)
    private String voucherNumber;

    @Size(max = 100)
    private String sheetNumber;

    @Size(max = 255)
    private String remarks;

    @NotEmpty
    @Valid
    @Size(max = 50)
    private List<IssuedTenderRequest> tenders;

    // Optional: apply the payment to the supplier's open payables now. Whatever is left unapplied stays as an
    // advance to the supplier (apply it later with a Supplier Credit Application).
    @Valid
    @Size(max = 500)
    private List<ApplicationRequest> applications;
}
