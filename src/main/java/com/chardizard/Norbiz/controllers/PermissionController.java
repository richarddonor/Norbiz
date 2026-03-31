package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.PermissionResponse;
import com.chardizard.Norbiz.repositories.PermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionRepository permissionRepository;

    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_ROLE')")
    public ResponseEntity<List<PermissionResponse>> getAll() {
        List<PermissionResponse> permissions = permissionRepository.findAll().stream()
                .map(p -> {
                    PermissionResponse r = new PermissionResponse();
                    r.setId(p.getId());
                    r.setName(p.getName());
                    return r;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(permissions);
    }
}