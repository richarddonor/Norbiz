package com.chardizard.Norbiz.config;

import io.micrometer.common.KeyValue;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Logs every incoming HTTP request (including its JSON payload) and its outcome
 * so a request can be followed end-to-end alongside the OpenTelemetry trace/span
 * ids attached to each log line.
 *
 * The request body can only be read once, so it's cached via ContentCachingRequestWrapper
 * and logged in the "after" phase, once a downstream @RequestBody read has populated the
 * cache — logging it "before" would always see an empty buffer.
 *
 * Ordered directly after Spring's ServerHttpObservationFilter (HIGHEST_PRECEDENCE + 1), so it
 * runs inside the request's server span (every line carries its traceId) yet before Spring
 * Security, so requests rejected there (401/403) are still logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@RequiredArgsConstructor
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final Logger log = LoggerFactory.getLogger("http.request");
    private static final int MAX_PAYLOAD_LENGTH = 4000;
    // Matches any JSON key containing "password"/"token"/"secret" as a substring — not just an
    // exact match — so fields like "newPassword"/"currentPassword" (added for change/reset
    // password) are redacted too, without needing every new field name enumerated here.
    private static final Pattern SENSITIVE_FIELD_PATTERN =
            Pattern.compile("(?i)(\"\\w*(password|token|secret)\\w*\"\\s*:\\s*)\"[^\"]*\"");

    private final Tracer tracer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        boolean cacheBody = hasLoggableJsonBody(request);
        HttpServletRequest requestToUse = cacheBody
                ? new ContentCachingRequestWrapper(request, MAX_PAYLOAD_LENGTH)
                : request;

        // Set up front — the response may be committed before the "after" phase runs.
        Span span = tracer.currentSpan();
        if (span != null) {
            response.setHeader(TRACE_ID_HEADER, span.context().traceId());
        }

        long start = System.currentTimeMillis();
        log.info("--> {} {}", request.getMethod(), request.getRequestURI());
        try {
            filterChain.doFilter(requestToUse, response);
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            if (cacheBody) {
                logPayload((ContentCachingRequestWrapper) requestToUse);
            }
            log.info("<-- {} {} {} ({} ms)", request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
        }
    }

    private boolean hasLoggableJsonBody(HttpServletRequest request) {
        String method = request.getMethod();
        String contentType = request.getContentType();
        boolean mutatingMethod = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
        return mutatingMethod && contentType != null && contentType.startsWith("application/json");
    }

    private void logPayload(ContentCachingRequestWrapper wrapper) {
        // The wrapper itself caps its buffer at MAX_PAYLOAD_LENGTH bytes, so a large
        // body is transparently truncated here rather than by this method.
        byte[] content = wrapper.getContentAsByteArray();
        if (content.length == 0) {
            return;
        }
        String payload = SENSITIVE_FIELD_PATTERN.matcher(new String(content, StandardCharsets.UTF_8))
                .replaceAll("$1\"***\"");
        log.info("--> {} {} payload: {}", wrapper.getMethod(), wrapper.getRequestURI(), payload);
        // Also attach it to the request span so it's visible on the trace itself. High-cardinality
        // key values only go to spans, never to metric tags.
        ServerHttpObservationFilter.findObservationContext(wrapper)
                .ifPresent(context -> context.addHighCardinalityKeyValue(KeyValue.of("http.request.body", payload)));
    }
}