package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.RoleRequest;
import com.chardizard.Norbiz.dto.RoleResponse;
import com.chardizard.Norbiz.models.Role;
import com.chardizard.Norbiz.services.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "Roles", description = "Role and permission management — requires VIEW_ROLE / CREATE_ROLE / UPDATE_ROLE / DELETE_ROLE permissions")
@RestController
@RequestMapping("/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @Operation(summary = "List roles", description = "Returns all roles except SUPER_ADMIN.")
    @ApiResponse(responseCode = "200", description = "Role list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ROLE permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_ROLE')")
    public ResponseEntity<List<RoleResponse>> getAll() {
        List<RoleResponse> roles = roleService.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(roles);
    }

    @Operation(summary = "Get role by ID")
    @ApiResponse(responseCode = "200", description = "Role returned")
    @ApiResponse(responseCode = "404", description = "Role not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_ROLE')")
    public ResponseEntity<RoleResponse> getById(@Parameter(description = "Role ID") @PathVariable Long id) {
        return ResponseEntity.ok(toResponse(roleService.findById(id)));
    }

    @Operation(summary = "Create role", description = "Creates a new role with optional initial permission assignments.")
    @ApiResponse(responseCode = "201", description = "Role created")
    @ApiResponse(responseCode = "403", description = "Missing CREATE_ROLE permission")
    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_ROLE')")
    public ResponseEntity<RoleResponse> create(@RequestBody RoleRequest request) {
        Role role = roleService.create(request.getName(), request.getDisplayName(), request.getPermissionIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(role));
    }

    @Operation(summary = "Update role", description = "Replaces the role's display name and full permission set.")
    @ApiResponse(responseCode = "200", description = "Role updated")
    @ApiResponse(responseCode = "403", description = "Missing UPDATE_ROLE permission")
    @ApiResponse(responseCode = "404", description = "Role not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_ROLE')")
    public ResponseEntity<RoleResponse> update(@Parameter(description = "Role ID") @PathVariable Long id, @RequestBody RoleRequest request) {
        Role role = roleService.update(id, request.getName(), request.getDisplayName(), request.getPermissionIds());
        return ResponseEntity.ok(toResponse(role));
    }

    @Operation(summary = "Delete role", description = "Deletes a role and removes it from all users.")
    @ApiResponse(responseCode = "204", description = "Role deleted")
    @ApiResponse(responseCode = "403", description = "Missing DELETE_ROLE permission")
    @ApiResponse(responseCode = "404", description = "Role not found")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE_ROLE')")
    public ResponseEntity<Void> delete(@Parameter(description = "Role ID") @PathVariable Long id) {
        roleService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private RoleResponse toResponse(Role role) {
        RoleResponse response = new RoleResponse();
        response.setId(role.getId());
        response.setName(role.getName());
        response.setDisplayName(role.getDisplayName());
        response.setPermissions(role.getPermissions().stream()
                .map(p -> p.getName())
                .collect(Collectors.toSet()));
        return response;
    }
}