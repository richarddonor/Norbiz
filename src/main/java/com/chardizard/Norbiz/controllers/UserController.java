package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.CreateUserRequest;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.dto.UpdateUserRequest;
import com.chardizard.Norbiz.dto.UserResponse;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.services.UserService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Tag(name = "Users", description = "User management — requires VIEW_USER / CREATE_USER / UPDATE_USER / DELETE_USER permissions")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "List users", description = "Returns all users visible to the caller. SUPER_ADMIN sees all; others see users within their companies.")
    @ApiResponse(responseCode = "200", description = "User list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_USER permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_USER')")
    public ResponseEntity<AppResponse<PageResponse<UserResponse>>> getAll(
            @Parameter(description = "Filter by display name (contains)") @RequestParam(required = false) String displayName,
            @Parameter(description = "Filter by username (contains)") @RequestParam(required = false) String username,
            @Parameter(description = "Filter by email (contains)") @RequestParam(required = false) String email,
            @Parameter(description = "Filter by role name (contains)") @RequestParam(required = false) String roles,
            @Parameter(description = "Filter by company name (contains)") @RequestParam(required = false) String companies,
            Pageable pageable) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (StringUtils.hasText(displayName)) filters.put("displayName", displayName);
        if (StringUtils.hasText(username)) filters.put("username", username);
        if (StringUtils.hasText(email)) filters.put("email", email);
        if (StringUtils.hasText(roles)) filters.put("roles", roles);
        if (StringUtils.hasText(companies)) filters.put("companies", companies);

        var users = userService.findAll(filters, pageable).map(this::toResponse);
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(users)));
    }

    @Operation(summary = "Create user", description = "Creates a new user. SUPER_ADMIN may assign any companies; others assign the user to their own company.")
    @ApiResponse(responseCode = "201", description = "User created")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_USER permission")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_USER')")
    public ResponseEntity<AppResponse<UserResponse>> createUser(@Valid @RequestBody CreateUserRequest request,
                                                                @AuthenticationPrincipal UserDetails userDetails) {
        User caller = userService.findByUsername(userDetails.getUsername());
        Set<Long> companyIds = userService.resolveCompanyIds(caller, request.getCompanyIds());

        User user = new User();
        user.setUsername(request.getUsername());
        user.setDisplayName(request.getDisplayName());
        user.setEmail(request.getEmail());
        user.setPassword(request.getPassword());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppResponse.of(toResponse(userService.register(user, companyIds))));
    }

    @Operation(summary = "Update user", description = "Updates display name, email, roles, and company assignments for an existing user.")
    @ApiResponse(responseCode = "200", description = "User updated")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_USER permission")
    @ApiResponse(responseCode = "404", description = "User not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_USER')")
    public ResponseEntity<AppResponse<UserResponse>> updateUser(@Parameter(description = "User ID") @PathVariable Long id,
                                                                @Valid @RequestBody UpdateUserRequest request,
                                                                @AuthenticationPrincipal UserDetails userDetails) {
        User caller = userService.findByUsername(userDetails.getUsername());
        Set<Long> companyIds = userService.resolveCompanyIds(caller, request.getCompanyIds());

        User user = userService.update(id, request.getUsername(), request.getDisplayName(),
                request.getEmail(), request.getRoleIds(), companyIds);
        return ResponseEntity.ok(AppResponse.of(toResponse(user)));
    }

    @Operation(summary = "Delete user", description = "Permanently deletes a user.")
    @ApiResponse(responseCode = "204", description = "User deleted")
    @ApiResponse(responseCode = "403", description = "Missing DELETE_USER permission")
    @ApiResponse(responseCode = "404", description = "User not found")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE_USER')")
    public ResponseEntity<Void> deleteUser(@Parameter(description = "User ID") @PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private UserResponse toResponse(User user) {
        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setDisplayName(user.getDisplayName());
        response.setEmail(user.getEmail());
        response.setRoles(user.getRoles().stream()
                .map(r -> r.getDisplayName() != null ? r.getDisplayName() : r.getName())
                .collect(Collectors.toSet()));
        response.setRoleIds(user.getRoles().stream()
                .map(r -> r.getId())
                .collect(Collectors.toSet()));
        response.setCompanies(user.getCompanies().stream()
                .map(c -> {
                    UserResponse.CompanyInfo info = new UserResponse.CompanyInfo();
                    info.setId(c.getId());
                    info.setName(c.getName());
                    return info;
                })
                .collect(Collectors.toList()));
        return response;
    }
}