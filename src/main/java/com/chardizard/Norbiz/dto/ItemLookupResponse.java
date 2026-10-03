package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.ItemTag;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Set;

// Item dropdown option. tags let transaction forms keep only INVENTORY items; costPrice preloads a
// line's unit cost and is null unless the caller holds VIEW_COST_PRICE. No other prices are exposed.
@Getter
@AllArgsConstructor
@NoArgsConstructor // for reading cached entries back from Redis
public class ItemLookupResponse {
    private Long id;
    private Long companyId;
    private String code;
    private String name;
    private boolean active;
    private Set<ItemTag> tags;
    private BigDecimal costPrice;
}
