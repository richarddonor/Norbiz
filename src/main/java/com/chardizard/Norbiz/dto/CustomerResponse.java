package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.CustomerType;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class CustomerResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private String code;
    private String name;
    private CustomerType type;
    private String email;
    private String phone;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}