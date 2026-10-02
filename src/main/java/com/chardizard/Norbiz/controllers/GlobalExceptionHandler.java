package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppErrorResponse;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.ServerHttpObservationFilter;

import java.util.stream.Collectors;

@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Tracer tracer;

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AppErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(error(ex.getMessage()));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<AppErrorResponse> handleSecurity(SecurityException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(ex.getMessage()));
    }

    // Thrown by @PreAuthorize when the caller lacks the required permission entirely.
    // Method-security denials happen inside the controller invocation (unlike URL-level
    // security rules), so they reach this advice rather than Spring Security's own
    // filter-chain exception translation — without this handler they'd fall through to
    // the generic 500 handler below instead of a 403.
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<AppErrorResponse> handleAuthorizationDenied(AuthorizationDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("Access denied"));
    }

    // Thrown by AuthenticationManager.authenticate() on failed login (bad credentials, disabled or
    // locked account). It's raised inside the controller, so — like AuthorizationDeniedException —
    // it never reaches Spring Security's entry point and would otherwise fall through to the 500
    // handler. The message is deliberately generic so it can't be used to probe which usernames exist.
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<AppErrorResponse> handleAuthentication(AuthenticationException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("Invalid username or password"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AppErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", message);
        return ResponseEntity.badRequest().body(error(message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AppErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error", ex);
        // An exception handled here never propagates back to the HTTP observation filter, so the
        // request span would otherwise end looking successful. setError() only feeds the span/metric
        // "exception" tag; span.error() is what sets ERROR status and records the stack trace event.
        ServerHttpObservationFilter.findObservationContext(request).ifPresent(context -> {
            context.setError(ex);
            TracingObservationHandler.TracingContext tracingContext =
                    context.get(TracingObservationHandler.TracingContext.class);
            if (tracingContext != null && tracingContext.getSpan() != null) {
                tracingContext.getSpan().error(ex);
            }
        });
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("An unexpected error occurred"));
    }

    private AppErrorResponse error(String message) {
        Span span = tracer.currentSpan();
        return AppErrorResponse.of(message, span != null ? span.context().traceId() : null);
    }
}
