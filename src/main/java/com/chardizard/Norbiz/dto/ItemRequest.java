package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.ItemTag;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Set;

@Getter
@Setter
public class ItemRequest {
    private Long companyId;
    private String itemCode;
    private String name;

    @NotNull
    private Long itemCategoryId;
    private String imagePath;
    private List<String> skus;
    private List<PriceRequest> prices;
    private Set<ItemTag> tags;
}
