package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppResponse;
import com.chardizard.Norbiz.dto.AuthRequest;
import com.chardizard.Norbiz.dto.AuthResponse;
import com.chardizard.Norbiz.dto.ChangePasswordRequest;
import com.chardizard.Norbiz.dto.MeResponse;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.security.JwtUtil;
import com.chardizard.Norbiz.services.UserDetailsServiceImpl;
import com.chardizard.Norbiz.services.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "Auth", description = "Authentication and current-user endpoints")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final UserDetailsServiceImpl userDetailsService;
    private final UserService userService;
    private final JwtUtil jwtUtil;

    @Operation(summary = "Login", description = "Authenticate with username and password. Returns a JWT and the list of companies the user belongs to.")
    @ApiResponse(responseCode = "200", description = "Authentication successful")
    @ApiResponse(responseCode = "401", description = "Invalid credentials")
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<AppResponse<AuthResponse>> login(@Valid @RequestBody AuthRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );
        } catch (AuthenticationException ex) {
            log.warn("Failed login attempt for user '{}'", request.getUsername());
            throw ex;
        }
        UserDetails userDetails = userDetailsService.loadUserByUsername(request.getUsername());
        User user = userService.findByUsername(request.getUsername());
        String token = jwtUtil.generateToken(userDetails, user.getDisplayName());

        List<AuthResponse.CompanyInfo> companies = user.getCompanies().stream()
                .map(c -> new AuthResponse.CompanyInfo(c.getId(), c.getName()))
                .collect(Collectors.toList());

        log.info("User '{}' logged in successfully", request.getUsername());
        return ResponseEntity.ok(AppResponse.of(new AuthResponse(token, companies, companies.size() > 1)));
    }

    @Operation(summary = "Current user", description = "Returns the authenticated user's profile, roles, permissions, and accessible companies.")
    @ApiResponse(responseCode = "200", description = "Profile returned")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @GetMapping("/me")
    public ResponseEntity<AppResponse<MeResponse>> me(@AuthenticationPrincipal UserDetails userDetails) {
        User user = userService.findByUsername(userDetails.getUsername());

        MeResponse me = new MeResponse();
        me.setUsername(user.getUsername());
        me.setDisplayName(user.getDisplayName());
        me.setRoles(user.getRoles().stream()
                .map(r -> r.getDisplayName() != null ? r.getDisplayName() : r.getName())
                .collect(Collectors.toList()));
        me.setPermissions(user.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(p -> p.getName())
                .collect(Collectors.toSet()));
        me.setCompanies(user.getCompanies().stream()
                .map(c -> {
                    MeResponse.CompanyInfo info = new MeResponse.CompanyInfo();
                    info.setId(c.getId());
                    info.setName(c.getName());
                    return info;
                })
                .collect(Collectors.toList()));

        return ResponseEntity.ok(AppResponse.of(me));
    }

    @Operation(summary = "Change own password", description = "Lets the currently authenticated user change their own password. Requires the current password.")
    @ApiResponse(responseCode = "200", description = "Password changed")
    @ApiResponse(responseCode = "400", description = "Current password is incorrect")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @PostMapping("/change-password")
    public ResponseEntity<AppResponse<String>> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                               @AuthenticationPrincipal UserDetails userDetails) {
        userService.changePassword(userDetails.getUsername(), request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.ok(AppResponse.of("Password changed successfully."));
    }
}