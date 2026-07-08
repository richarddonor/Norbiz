package com.chardizard.Norbiz.dto;

import com.chardizard.Norbiz.models.EmployeeTag;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Getter
@Setter
public class EmployeeRequest {

    @NotNull
    private Long companyId;

    private Long userId;

    @NotBlank
    @Size(max = 100)
    private String employeeCode;

    @NotBlank
    @Size(max = 255)
    private String firstName;

    @NotBlank
    @Size(max = 255)
    private String lastName;

    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 50)
    private String phone;

    private boolean active = true;

    private Set<EmployeeTag> tags;
}