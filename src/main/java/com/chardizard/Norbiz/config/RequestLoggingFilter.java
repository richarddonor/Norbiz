package com.chardizard.Norbiz.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
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
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("http.request");
    private static final int MAX_PAYLOAD_LENGTH = 4000;
    private static final Pattern SENSITIVE_FIELD_PATTERN =
            Pattern.compile("(?i)(\"(password|token|secret)\"\\s*:\\s*)\"[^\"]*\"");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        boolean cacheBody = hasLoggableJsonBody(request);
        HttpServletRequest requestToUse = cacheBody
                ? new ContentCachingRequestWrapper(request, MAX_PAYLOAD_LENGTH)
                : request;

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
        String payload = new String(content, StandardCharsets.UTF_8);
        payload = SENSITIVE_FIELD_PATTERN.matcher(payload).replaceAll("$1\"***\"");
        log.info("--> {} {} payload: {}", wrapper.getMethod(), wrapper.getRequestURI(), payload);
    }
}