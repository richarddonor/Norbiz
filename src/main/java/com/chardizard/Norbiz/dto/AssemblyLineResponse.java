package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class AssemblyLineResponse {
    private Long id;
    private Long itemId;
    private String itemCode;
    private String itemName;
    private BigDecimal quantity;
    private Integer lineNumber;
    // Output lines only.
    private Long billOfMaterialId;
    private String billOfMaterialCode;
}
