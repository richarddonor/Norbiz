package com.chardizard.Norbiz.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@Getter
@RequiredArgsConstructor
public class AppErrorResponse {
    private final String message;

    // OpenTelemetry trace id of the failed request — quote it to find the full trace/logs.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String traceId;

    // Machine-readable error code for errors the frontend handles specially (e.g. ENTITY_IN_USE).
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String code;

    // Structured context for the code, e.g. {entity, entityId, referencedBy} for ENTITY_IN_USE.
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final Map<String, Object> details;

    public static AppErrorResponse of(String message, String traceId) {
        return new AppErrorResponse(message, traceId, null, null);
    }

    public static AppErrorResponse of(String message, String traceId, String code, Map<String, Object> details) {
        return new AppErrorResponse(message, traceId, code, details);
    }
}
