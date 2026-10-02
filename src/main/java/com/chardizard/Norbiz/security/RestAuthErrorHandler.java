package com.chardizard.Norbiz.security;

import com.chardizard.Norbiz.dto.AppErrorResponse;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Writes the response for requests rejected by URL-level security rules in the filter chain,
 * before any controller runs (so GlobalExceptionHandler never sees them):
 * - no/invalid/expired token on a protected path → 401 (without this, Spring Security's default
 *   entry point is Http403ForbiddenEntryPoint, since no httpBasic/formLogin is configured);
 * - authenticated but lacking the role a path requires (e.g. /admin/**) → 403.
 * Both return an AppErrorResponse body like every other API error.
 */
@Component
@RequiredArgsConstructor
public class RestAuthErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RestAuthErrorHandler.class);

    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.warn("Unauthenticated request to {} {}", request.getMethod(), request.getRequestURI());
        write(response, HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.warn("Access denied to {} {}", request.getMethod(), request.getRequestURI());
        write(response, HttpStatus.FORBIDDEN, "Access denied");
    }

    private void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        Span span = tracer.currentSpan();
        AppErrorResponse body = AppErrorResponse.of(message, span != null ? span.context().traceId() : null);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
