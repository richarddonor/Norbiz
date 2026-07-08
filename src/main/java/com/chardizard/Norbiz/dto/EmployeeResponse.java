package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.EmployeeTag;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Set;

@Getter
@Setter
public class EmployeeResponse {
    private Long id;
    private Long companyId;
    private String companyName;
    private Long userId;
    private String username;
    private String employeeCode;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private boolean active;
    private Set<EmployeeTag> tags;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}