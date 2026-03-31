package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.RoleRequest;
import com.chardizard.Norbiz.dto.RoleResponse;
import com.chardizard.Norbiz.models.Role;
import com.chardizard.Norbiz.services.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_ROLE')")
    public ResponseEntity<List<RoleResponse>> getAll() {
        List<RoleResponse> roles = roleService.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(roles);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_ROLE')")
    public ResponseEntity<RoleResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(toResponse(roleService.findById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_ROLE')")
    public ResponseEntity<RoleResponse> create(@RequestBody RoleRequest request) {
        Role role = roleService.create(request.getName(), request.getDisplayName(), request.getPermissionIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(role));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_ROLE')")
    public ResponseEntity<RoleResponse> update(@PathVariable Long id, @RequestBody RoleRequest request) {
        Role role = roleService.update(id, request.getName(), request.getDisplayName(), request.getPermissionIds());
        return ResponseEntity.ok(toResponse(role));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE_ROLE')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
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