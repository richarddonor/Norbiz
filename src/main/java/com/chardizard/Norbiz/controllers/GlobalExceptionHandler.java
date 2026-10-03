package com.chardizard.Norbiz.controllers;

import com.chardizard.Norbiz.dto.AppErrorResponse;
import com.chardizard.Norbiz.exceptions.EntityInUseException;
import com.chardizard.Norbiz.util.ForeignKeyViolations;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.ServerHttpObservationFilter;

import java.util.LinkedHashMap;
import java.util.Map;
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

    // Constraint violations on @RequestParam/@PathVariable (e.g. @Size on a lookup's ?q=), which Spring
    // MVC's built-in method validation raises instead of MethodArgumentNotValidException.
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<AppErrorResponse> handleMethodValidation(HandlerMethodValidationException ex) {
        String message = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> r.getMethodParameter().getParameterName() + ": " + e.getDefaultMessage()))
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", message);
        return ResponseEntity.badRequest().body(error(message));
    }

    // e.g. an unknown enum value in a path variable/query param (/transactions/NOT_A_TYPE/1/history).
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<AppErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = "Invalid value for " + ex.getName() + ": " + ex.getValue();
        log.warn("Bad request: {}", message);
        return ResponseEntity.badRequest().body(error(message));
    }

    // A required @RequestParam is absent (e.g. /lookups/stock without warehouseId).
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<AppErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        String message = ex.getParameterName() + " is required";
        log.warn("Bad request: {}", message);
        return ResponseEntity.badRequest().body(error(message));
    }

    // Malformed JSON or a value that can't be bound to the field type (e.g. unknown enum in a request body).
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AppErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Bad request: unreadable body: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(error("Malformed request body or invalid field value"));
    }

    // Deleting a record that other records still reference. Services raise this via
    // ForeignKeyViolations.deleteOrThrow (or an explicit pre-check) so the frontend can show which
    // record type is blocking the delete.
    @ExceptionHandler(EntityInUseException.class)
    public ResponseEntity<AppErrorResponse> handleEntityInUse(EntityInUseException ex) {
        log.warn("Delete blocked: {} (entity={}, id={}, referencedBy={})",
                ex.getMessage(), ex.getEntity(), ex.getEntityId(), ex.getReferencedBy());
        Map<String, Object> details = new LinkedHashMap<>();
        if (ex.getEntity() != null) details.put("entity", ex.getEntity());
        if (ex.getEntityId() != null) details.put("entityId", ex.getEntityId());
        if (ex.getReferencedBy() != null) details.put("referencedBy", ex.getReferencedBy());
        Span span = tracer.currentSpan();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(AppErrorResponse.of(
                ex.getMessage(), span != null ? span.context().traceId() : null, EntityInUseException.CODE, details));
    }

    // Fallback for FK violations that weren't translated in the service (e.g. a delete path that
    // doesn't use ForeignKeyViolations.deleteOrThrow, so the violation only surfaces at commit).
    // Any other integrity violation is still unexpected and goes to the generic handler.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<AppErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        return ForeignKeyViolations.from(ex, null, null)
                .map(this::handleEntityInUse)
                .orElseGet(() -> handleGeneric(ex, request));
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
