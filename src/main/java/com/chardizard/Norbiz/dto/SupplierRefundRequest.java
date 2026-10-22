package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.ReceivedPaymentMode;
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
public class SupplierRefundRequest {

    @NotNull
    private Long companyId;

    @NotNull
    private Long supplierId;

    @NotBlank
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String refundDate;

    @Size(max = 100)
    private String sheetNumber;

    @Size(max = 255)
    private String remarks;

    // How the supplier paid us back.
    @NotNull
    private ReceivedPaymentMode mode;

    // Required unless CASH/OTHERS: our account it was deposited to / landed in.
    private Long bankAccountId;

    @Size(max = 50)
    private String checkNumber;

    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String checkDate;

    // CHECK only: the drawee bank.
    private Long bankId;

    @NotEmpty
    @Valid
    @Size(max = 100)
    private List<RefundLineRequest> lines;
}
