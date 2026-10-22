package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.OpenItemSourceType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CreditApplicationLineResponse {
    private Long id;
    private Long creditItemId;
    private OpenItemSourceType creditSourceType;
    private String creditReferenceNumber;
    private Long chargeItemId;
    private OpenItemSourceType chargeSourceType;
    private String chargeReferenceNumber;
    private BigDecimal amount;
    private Integer lineNumber;
}
