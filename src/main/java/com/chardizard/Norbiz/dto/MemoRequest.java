package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.MemoType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

// Fields shared by customer and supplier memos.
@Getter
@Setter
public abstract class MemoRequest {

    @NotNull
    private Long companyId;

    @NotNull
    private MemoType memoType;

    @NotBlank
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String memoDate;

    // Charge-side memos only: payment terms in days (defaults to the party's terms).
    @Min(0)
    @Max(365)
    private Integer termDays;

    // Charge-side memos only (yyyy-MM-dd); defaults to memoDate + termDays. OPENING_BALANCE memos use it to age the
    // carried-over balance by its original due date.
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String dueDate;

    // OPENING_BALANCE memos: the legacy document number the balance came from.
    @Size(max = 100)
    private String originalReference;

    @Size(max = 100)
    private String sheetNumber;

    @Size(max = 255)
    private String remarks;

    @NotEmpty
    @Valid
    @Size(max = 200)
    private List<AmountLineRequest> lines;

    // Credit-side memos only: apply the credit to open charges of the same party right away.
    @Valid
    @Size(max = 500)
    private List<ApplicationRequest> applications;
}
