package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class MemoLineResponse {
    private Long id;
    private String description;
    private BigDecimal amount;
    private Integer lineNumber;
}
