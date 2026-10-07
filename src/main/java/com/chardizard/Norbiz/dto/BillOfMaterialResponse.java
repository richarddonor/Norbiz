package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class BillOfMaterialResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private String code;
    private Long itemId;
    private String itemCode;
    private String itemName;
    private boolean active;
    private List<Component> components;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;

    @Getter
    @Setter
    public static class Component {
        private Long id;
        private Integer lineNumber;
        private Long itemId;
        private String itemCode;
        private String itemName;
        private BigDecimal quantity;
    }
}
