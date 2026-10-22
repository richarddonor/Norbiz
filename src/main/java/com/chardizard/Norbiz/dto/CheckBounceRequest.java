package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.BounceReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CheckBounceRequest {

    @NotBlank
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be yyyy-MM-dd")
    private String bouncedDate;

    @NotNull
    private BounceReason reason;

    @Size(max = 200)
    private String remarks;
}
