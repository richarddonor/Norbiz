package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.EmployeeRequest;
import com.chardizard.Norbiz.dto.EmployeeResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.Employee;
import com.chardizard.Norbiz.services.EmployeeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Employees", description = "Employee management — requires VIEW_EMPLOYEE / CREATE_EMPLOYEE / UPDATE_EMPLOYEE / DELETE_EMPLOYEE permissions.")
@RestController
@RequestMapping("/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    @Operation(summary = "List employees", description = "Returns employees belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Employee list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_EMPLOYEE permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_EMPLOYEE')")
    public ResponseEntity<AppResponse<PageResponse<EmployeeResponse>>> getAll(@AuthenticationPrincipal UserDetails userDetails,
                                                                              Pageable pageable) {
        var employees = employeeService.findAllForUser(userDetails.getUsername(), pageable).map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(employees)));
    }

    @Operation(summary = "Get employee by ID")
    @ApiResponse(responseCode = "200", description = "Employee returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_EMPLOYEE permission")
    @ApiResponse(responseCode = "404", description = "Employee not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_EMPLOYEE')")
    public ResponseEntity<AppResponse<EmployeeResponse>> getById(@Parameter(description = "Employee ID") @PathVariable Long id) {
        return ResponseEntity.ok(AppResponse.of(toResponse(employeeService.findById(id))));
    }

    @Operation(summary = "Create employee")
    @ApiResponse(responseCode = "201", description = "Employee created")
    @ApiResponse(responseCode = "400", description = "Employee code already exists for this company")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_EMPLOYEE permission")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_EMPLOYEE')")
    public ResponseEntity<AppResponse<EmployeeResponse>> create(@Valid @RequestBody EmployeeRequest request,
                                                                @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(employeeService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Update employee")
    @ApiResponse(responseCode = "200", description = "Employee updated")
    @ApiResponse(responseCode = "400", description = "Employee code already exists for this company")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_EMPLOYEE permission")
    @ApiResponse(responseCode = "404", description = "Employee not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_EMPLOYEE')")
    public ResponseEntity<AppResponse<EmployeeResponse>> update(@Parameter(description = "Employee ID") @PathVariable Long id,
                                                                @Valid @RequestBody EmployeeRequest request,
                                                                @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(employeeService.update(id, request, userDetails.getUsername()))));
    }

    @Operation(summary = "Delete employee")
    @ApiResponse(responseCode = "204", description = "Employee deleted")
    @ApiResponse(responseCode = "403", description = "Missing DELETE_EMPLOYEE permission")
    @ApiResponse(responseCode = "404", description = "Employee not found")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE_EMPLOYEE')")
    public ResponseEntity<Void> delete(@Parameter(description = "Employee ID") @PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        employeeService.delete(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    private EmployeeResponse toResponse(Employee employee) {
        EmployeeResponse res = new EmployeeResponse();
        res.setId(employee.getId());
        res.setCompanyId(employee.getCompany().getId());
        res.setCompanyName(employee.getCompany().getName());
        res.setEmployeeCode(employee.getEmployeeCode());
        res.setFirstName(employee.getFirstName());
        res.setLastName(employee.getLastName());
        res.setEmail(employee.getEmail());
        res.setPhone(employee.getPhone());
        res.setActive(employee.isActive());
        res.setTags(employee.getTags());
        if (employee.getUser() != null) {
            res.setUserId(employee.getUser().getId());
            res.setUsername(employee.getUser().getUsername());
        }
        res.setCreatedAt(employee.getCreatedAt());
        res.setUpdatedAt(employee.getUpdatedAt());
        res.setCreatedBy(employee.getCreatedBy());
        res.setUpdatedBy(employee.getUpdatedBy());
        return res;
    }
}