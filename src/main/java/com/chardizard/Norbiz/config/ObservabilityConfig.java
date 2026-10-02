package com.chardizard.Norbiz.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenTelemetry wiring that Spring Boot doesn't auto-configure. Tracing, metrics and the
 * OTLP exporters themselves come from spring-boot-starter-opentelemetry and are driven by
 * the management.* properties in application.properties (see docs/OBSERVABILITY.md).
 */
@Configuration
public class ObservabilityConfig {

    private static final String OTEL_APPENDER_NAME = "OTEL";

    /**
     * Attaches the OpenTelemetry Logback appender to the root logger so every log event is
     * also emitted as an OTel log record (shipped over OTLP when log export is enabled),
     * carrying the trace/span id of the request that produced it.
     *
     * Attached programmatically rather than via logback-spring.xml so Spring Boot keeps
     * owning the console appender — including logging.structured.format.console.
     */
    @Bean
    public InitializingBean openTelemetryLogAppender(OpenTelemetry openTelemetry) {
        return () -> {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            Logger rootLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            // Test suites can start several application contexts in one JVM — don't stack appenders.
            if (rootLogger.getAppender(OTEL_APPENDER_NAME) != null) {
                return;
            }
            OpenTelemetryAppender appender = new OpenTelemetryAppender();
            appender.setName(OTEL_APPENDER_NAME);
            appender.setContext(loggerContext);
            appender.setOpenTelemetry(openTelemetry);
            appender.setCaptureExperimentalAttributes(true);   // thread name/id
            appender.setCaptureKeyValuePairAttributes(true);   // SLF4J fluent addKeyValue(...)
            appender.start();
            rootLogger.addAppender(appender);
        };
    }
}
