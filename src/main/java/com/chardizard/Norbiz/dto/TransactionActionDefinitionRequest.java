package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.TransactionType;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
public class TransactionActionDefinitionRequest {

    @NotNull
    private Long companyId;

    @NotNull
    private TransactionType transactionType;

    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "must be UPPER_SNAKE_CASE (e.g. BARCODE_PRINTING)")
    private String code;

    @NotBlank
    @Size(max = 255)
    private String name;

    @NotNull
    @Min(0)
    @Max(9999)
    private Integer sortOrder;

    @NotNull
    private Boolean active;

    @NotEmpty
    @Size(max = 50)
    private Set<@NotNull Long> allowedRoleIds = new HashSet<>();

    @Size(max = 50)
    private Set<@NotNull Long> prerequisiteIds = new HashSet<>();
}
