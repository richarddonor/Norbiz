package com.chardizard.Norbiz.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class SupplierResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private String code;
    private String name;
    private String email;
    private String phone;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}