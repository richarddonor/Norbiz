package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.CustomerRequest;
import com.chardizard.Norbiz.dto.CustomerResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.models.Customer;
import com.chardizard.Norbiz.services.CustomerService;
import com.chardizard.Norbiz.util.DateRangeUtils;
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
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "Customers", description = "Customer/Outlet management — requires VIEW_CUSTOMER / CREATE_CUSTOMER / UPDATE_CUSTOMER / DELETE_CUSTOMER permissions.")
@RestController
@RequestMapping("/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @Operation(summary = "List customers", description = "Returns customers/outlets belonging to the caller's accessible companies. SUPER_ADMIN sees all.")
    @ApiResponse(responseCode = "200", description = "Customer list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_CUSTOMER permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_CUSTOMER')")
    public ResponseEntity<AppResponse<PageResponse<CustomerResponse>>> getAll(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Filter by name (contains)") @RequestParam(required = false) String name,
            @Parameter(description = "Filter by code (contains)") @RequestParam(required = false) String code,
            @Parameter(description = "Filter by type (contains, e.g. CUSTOMER or OUTLET)") @RequestParam(required = false) String type,
            @Parameter(description = "Filter by company name (contains)") @RequestParam(required = false) String company,
            @Parameter(description = "Filter by creator (contains)") @RequestParam(required = false) String createdBy,
            @Parameter(description = "Filter by active status (true or false)") @RequestParam(required = false) String active,
            @Parameter(description = "Filter by last-updated date, range start (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtFrom,
            @Parameter(description = "Filter by last-updated date, range end (yyyy-MM-dd, inclusive)") @RequestParam(required = false) String updatedAtTo,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(name)) filters.put("name", name);
        if (StringUtils.hasText(code)) filters.put("code", code);
        if (StringUtils.hasText(type)) filters.put("type", type);
        if (StringUtils.hasText(company)) filters.put("company", company);
        if (StringUtils.hasText(createdBy)) filters.put("createdBy", createdBy);
        if (StringUtils.hasText(active)) filters.put("active", active);

        Instant fromInstant = DateRangeUtils.startOfDayUtc(updatedAtFrom);
        Instant toInstant = DateRangeUtils.endOfDayUtc(updatedAtTo);

        var customers = customerService.findAllForUser(userDetails.getUsername(), filters, fromInstant, toInstant, pageable).map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(customers)));
    }

    @Operation(summary = "Get customer by ID")
    @ApiResponse(responseCode = "200", description = "Customer returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_CUSTOMER permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Customer not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_CUSTOMER')")
    public ResponseEntity<AppResponse<CustomerResponse>> getById(@Parameter(description = "Customer ID") @PathVariable Long id,
                                                                 @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(customerService.findById(id, userDetails.getUsername()))));
    }

    @Operation(summary = "Create customer", description = "Customer code (if supplied) must be unique per company.")
    @ApiResponse(responseCode = "201", description = "Customer created")
    @ApiResponse(responseCode = "400", description = "Customer code already exists for this company")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_CUSTOMER permission or no access to company")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_CUSTOMER')")
    public ResponseEntity<AppResponse<CustomerResponse>> create(@Valid @RequestBody CustomerRequest request,
                                                                @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(customerService.create(request, userDetails.getUsername()))));
    }

    @Operation(summary = "Update customer")
    @ApiResponse(responseCode = "200", description = "Customer updated")
    @ApiResponse(responseCode = "400", description = "Customer code already exists for this company")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_CUSTOMER permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Customer not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_CUSTOMER')")
    public ResponseEntity<AppResponse<CustomerResponse>> update(@Parameter(description = "Customer ID") @PathVariable Long id,
                                                                @Valid @RequestBody CustomerRequest request,
                                                                @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(AppResponse.of(toResponse(customerService.update(id, request, userDetails.getUsername()))));
    }

    @Operation(summary = "Delete customer")
    @ApiResponse(responseCode = "204", description = "Customer deleted")
    @ApiResponse(responseCode = "403", description = "Missing DELETE_CUSTOMER permission or no access to company")
    @ApiResponse(responseCode = "404", description = "Customer not found")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE_CUSTOMER')")
    public ResponseEntity<Void> delete(@Parameter(description = "Customer ID") @PathVariable Long id,
                                       @AuthenticationPrincipal UserDetails userDetails) {
        customerService.delete(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    private CustomerResponse toResponse(Customer customer) {
        CustomerResponse res = new CustomerResponse();
        res.setId(customer.getId());
        res.setCompanyId(customer.getCompany().getId());
        res.setCompanyName(customer.getCompany().getName());
        res.setCode(customer.getCode());
        res.setName(customer.getName());
        res.setType(customer.getType());
        res.setEmail(customer.getEmail());
        res.setPhone(customer.getPhone());
        res.setActive(customer.isActive());
        res.setCreatedAt(customer.getCreatedAt());
        res.setUpdatedAt(customer.getUpdatedAt());
        res.setCreatedBy(customer.getCreatedBy());
        res.setUpdatedBy(customer.getUpdatedBy());
        return res;
    }
}