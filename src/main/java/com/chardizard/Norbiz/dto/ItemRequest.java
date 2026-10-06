package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.ItemTag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Set;

@Getter
@Setter
public class ItemRequest {

    @NotNull
    private Long companyId;

    /** Fixed after creation — ignored on update. */
    @NotBlank
    @Size(max = 100)
    private String itemCode;

    @NotBlank
    @Size(max = 255)
    private String name;

    @NotNull
    private Long itemCategoryId;

    /** Optional — null leaves the item ungrouped. */
    private Long itemGroupId;

    /** Only applied on create; afterwards the image is managed by ItemImageController. */
    @Size(max = 500)
    private String imagePath;

    /**
     * The item's full set of SKUs, reconciled by code: existing codes are kept (unit price updated),
     * missing ones removed, new ones created. Null leaves the item's SKUs untouched.
     */
    @Valid
    private List<@NotNull ItemSkuLineRequest> skuLines;

    @Valid
    private List<@NotNull PriceRequest> prices;

    private Set<@NotNull ItemTag> tags;
}
