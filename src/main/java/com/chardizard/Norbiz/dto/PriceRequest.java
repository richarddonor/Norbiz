package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.PriceType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PriceRequest {
    private PriceType priceType;
    private BigDecimal amount;
}
