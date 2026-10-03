package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
public class ItemGroupResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private String name;
    private String description;
    private String bnInitials;
    private BigDecimal commissionRate;
    private BigDecimal focalCommissionRate;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
