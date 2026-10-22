package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.SupplierMemoReason;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierMemoRequest extends MemoRequest {

    @NotNull
    private Long supplierId;

    @NotNull
    private SupplierMemoReason reason;
}
