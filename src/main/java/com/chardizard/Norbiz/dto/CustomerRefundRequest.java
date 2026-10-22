package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.IssuedPaymentMode;
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
public class CustomerRefundRequest {

    @NotNull
    private Long companyId;

    @NotNull
    private Long customerId;

    @NotBlank
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String refundDate;

    @Size(max = 100)
    private String sheetNumber;

    @Size(max = 255)
    private String remarks;

    // How we pay the customer back. CHECK stays ISSUED until a Bank Reconciliation clears it.
    @NotNull
    private IssuedPaymentMode mode;

    // CHECK/BANK_TRANSFER: required, our account it is drawn on.
    private Long bankAccountId;

    @Size(max = 50)
    private String checkNumber;

    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String checkDate;

    @NotEmpty
    @Valid
    @Size(max = 100)
    private List<RefundLineRequest> lines;
}
