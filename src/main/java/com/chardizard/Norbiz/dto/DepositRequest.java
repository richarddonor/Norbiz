package com.chardizard.Norbiz.dto;

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
public class DepositRequest {

    @NotNull
    private Long companyId;

    // Our account the slip is deposited to.
    @NotNull
    private Long bankAccountId;

    @NotBlank
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String depositDate;

    // The bank's deposit slip number, if any.
    @Size(max = 100)
    private String sheetNumber;

    @Size(max = 255)
    private String remarks;

    // ON_HAND cash/check tenders of customer payments (GET /customer-payment-tenders?status=ON_HAND).
    @NotEmpty
    @Size(max = 500)
    private List<@NotNull Long> tenderIds;
}
