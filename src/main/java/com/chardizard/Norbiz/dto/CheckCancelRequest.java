package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.IssuedCancelReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CheckCancelRequest {

    @NotBlank
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String cancelledDate;

    @NotNull
    private IssuedCancelReason reason;

    @Size(max = 200)
    private String remarks;
}
