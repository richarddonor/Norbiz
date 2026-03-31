package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.CreateUserRequest;
import com.chardizard.Norbiz.dto.UpdateUserRequest;
import com.chardizard.Norbiz.dto.UserResponse;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.services.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_USER')")
    public ResponseEntity<List<UserResponse>> getAll() {
        List<UserResponse> users = userService.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_USER')")
    public ResponseEntity<Void> createUser(@RequestBody CreateUserRequest request) {
        User user = new User();
        user.setUsername(request.getUsername());
        user.setDisplayName(request.getDisplayName());
        user.setEmail(request.getEmail());
        user.setPassword(request.getPassword());
        userService.register(user);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('UPDATE_USER')")
    public ResponseEntity<UserResponse> updateUser(@PathVariable Long id, @RequestBody UpdateUserRequest request) {
        User user = userService.update(id, request.getUsername(), request.getDisplayName(), request.getEmail(), request.getRoleIds());
        return ResponseEntity.ok(toResponse(user));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE_USER')")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
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
        return response;
    }
}