package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.PriceType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class ItemResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private String itemCode;
    private String name;
    private String imagePath;
    private boolean active;
    private List<String> skus;
    private List<PriceEntry> prices;

    @Getter
    @Setter
    public static class PriceEntry {
        private PriceType priceType;
        private BigDecimal amount;
    }
}
