package com.chardizard.Norbiz.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

// Dropdown option for picking the bill of materials an Assembly output is built from. Carries the
// components so the form can prefill raw-material lines (component quantity x quantity assembled).
@Getter
@AllArgsConstructor
@NoArgsConstructor // for reading cached entries back from Redis
public class BillOfMaterialLookupResponse {
    private Long id;
    private Long companyId;
    private String code;
    private Long itemId;
    private String itemCode;
    private String itemName;
    private boolean active;
    private List<Component> components;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Component {
        private Long itemId;
        private String itemCode;
        private String itemName;
        // Per unit of the output item.
        private BigDecimal quantity;
    }
}
