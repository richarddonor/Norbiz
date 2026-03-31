package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AuthRequest;
import com.chardizard.Norbiz.dto.AuthResponse;
import com.chardizard.Norbiz.models.User;
import com.chardizard.Norbiz.security.JwtUtil;
import com.chardizard.Norbiz.services.UserDetailsServiceImpl;
import com.chardizard.Norbiz.services.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserDetailsServiceImpl userDetailsService;
    private final UserService userService;
    private final JwtUtil jwtUtil;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );
        UserDetails userDetails = userDetailsService.loadUserByUsername(request.getUsername());
        User user = userService.findByUsername(request.getUsername());
        String token = jwtUtil.generateToken(userDetails, user.getDisplayName());
        return ResponseEntity.ok(new AuthResponse(token));
    }
}
