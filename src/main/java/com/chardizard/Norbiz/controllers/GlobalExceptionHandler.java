package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AppErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(AppErrorResponse.of(ex.getMessage()));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<AppErrorResponse> handleSecurity(SecurityException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(AppErrorResponse.of(ex.getMessage()));
    }

    // Thrown by @PreAuthorize when the caller lacks the required permission entirely.
    // Method-security denials happen inside the controller invocation (unlike URL-level
    // security rules), so they reach this advice rather than Spring Security's own
    // filter-chain exception translation — without this handler they'd fall through to
    // the generic 500 handler below instead of a 403.
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<AppErrorResponse> handleAuthorizationDenied(AuthorizationDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(AppErrorResponse.of("Access denied"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AppErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", message);
        return ResponseEntity.badRequest().body(AppErrorResponse.of(message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AppErrorResponse> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AppErrorResponse.of("An unexpected error occurred"));
    }
}