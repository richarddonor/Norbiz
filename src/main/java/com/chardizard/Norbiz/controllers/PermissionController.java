package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.cache.CacheRegion;
import com.chardizard.Norbiz.cache.CacheScope;
import com.chardizard.Norbiz.cache.QueryCache;
import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.PageResponse;
import com.chardizard.Norbiz.dto.PermissionResponse;
import com.chardizard.Norbiz.repositories.PermissionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Permissions", description = "Permission catalogue — used when assigning permissions to roles")
@RestController
@RequestMapping("/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionRepository permissionRepository;
    private final QueryCache queryCache;

    @Operation(summary = "List permissions", description = "Returns all available permissions that can be assigned to roles.")
    @ApiResponse(responseCode = "200", description = "Permission list returned")
    @ApiResponse(responseCode = "403", description = "Missing VIEW_ROLE permission")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_ROLE')")
    public ResponseEntity<AppResponse<PageResponse<PermissionResponse>>> getAll(Pageable pageable) {
        var permissions = queryCache.page(CacheRegion.LIST_PERMISSION, CacheScope.global(), QueryCache.params(), pageable,
                PermissionResponse.class, () -> permissionRepository.findAll(pageable)
                .map(p -> {
                    PermissionResponse r = new PermissionResponse();
                    r.setId(p.getId());
                    r.setName(p.getName());
                    r.setDescription(p.getDescription());
                    return r;
                }));
        return ResponseEntity.ok(AppResponse.of(PageResponse.of(permissions)));
    }
}