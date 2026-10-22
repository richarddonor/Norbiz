package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.CustomerMemoReason;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CustomerMemoRequest extends MemoRequest {

    @NotNull
    private Long customerId;

    @NotNull
    private CustomerMemoReason reason;
}
