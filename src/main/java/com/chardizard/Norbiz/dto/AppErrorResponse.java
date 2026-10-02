package com.chardizard.Norbiz.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class AppErrorResponse {
    private final String message;

    // OpenTelemetry trace id of the failed request — quote it to find the full trace/logs.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String traceId;

    public static AppErrorResponse of(String message, String traceId) {
        return new AppErrorResponse(message, traceId);
    }
}
