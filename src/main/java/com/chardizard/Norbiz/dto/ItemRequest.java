package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ItemRequest {
    private Long companyId;
    private String itemCode;
    private String name;
    private String imagePath;
    private List<String> skus;
    private List<PriceRequest> prices;
}
