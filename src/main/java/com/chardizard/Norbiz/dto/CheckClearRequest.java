package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

// Marks checks cleared by hand (Bank Reconciliation clears them automatically).
@Getter
@Setter
public class CheckClearRequest {

    @NotEmpty
    @Size(max = 500)
    private List<@NotNull Long> tenderIds;

    @NotBlank
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String clearedDate;
}
