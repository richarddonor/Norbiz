package com.chardizard.Norbiz.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TransactionActionRequest {

    @NotNull
    private Long actionDefinitionId;

    @Size(max = 255)
    private String remarks;
}
