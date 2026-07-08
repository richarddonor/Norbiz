package com.chardizard.Norbiz.config;

import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Emits every finished span through SLF4J via the OpenTelemetry logging exporter,
 * so a request's full trace (spans + timings) is visible in the application logs
 * without requiring an external collector.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public SpanExporter loggingSpanExporter() {
        return LoggingSpanExporter.create();
    }
}