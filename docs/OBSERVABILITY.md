# Observability (OpenTelemetry)

Norbiz emits all three OpenTelemetry signals — traces, metrics and logs — over OTLP/HTTP. The app only ever talks to an OTLP endpoint (a Collector or an all-in-one backend); which backend stores the data is a deployment concern, not a code one.

## How it's wired

| Concern | Provided by |
|---|---|
| Tracer, OTLP exporters, resource attributes | `spring-boot-starter-opentelemetry` (Micrometer Observation → OTel bridge). **Not** the OTel Java agent — don't add it, it would double-instrument. |
| HTTP server spans | Spring's `ServerHttpObservationFilter` (order `HIGHEST_PRECEDENCE + 1`) |
| Spring Security spans | Spring Security's built-in observations |
| JDBC spans (connection checkout + each query) | `datasource-micrometer-spring-boot`, `jdbc.includes=CONNECTION,QUERY`. Bind values are never captured; query text is sanitized. |
| Logs → OTel log records | `opentelemetry-logback-appender-1.0`, attached to the root logger programmatically in `ObservabilityConfig` (not via `logback-spring.xml`, so Spring Boot keeps owning the console appender and `logging.structured.format.console`). |
| Metrics (HTTP, JVM, Hikari, Tomcat) | `micrometer-registry-otlp` |

Version coupling: `opentelemetry-logback-appender.version` in `pom.xml` must track the OTel SDK version that the Spring Boot BOM manages (instrumentation `2.21.x` ↔ SDK `1.55.0` for Boot 4.0.4). Re-check on every Spring Boot upgrade.

## Configuration

| Env var | Default | Effect |
|---|---|---|
| `OTEL_EXPORT_ENABLED` | `false` | Turns on OTLP export of traces, logs and metrics together. Off by default so a local run without a collector doesn't log connection errors. Spans/trace ids are still created when off. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | Base OTLP/HTTP URL; `/v1/traces`, `/v1/logs`, `/v1/metrics` are appended. |
| `DEPLOYMENT_ENVIRONMENT` | `local` | `deployment.environment.name` resource attribute. |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | unset (human-readable) | Set to `ecs` in containers for JSON console logs with `traceId`/`spanId` fields. |
| `OTEL_RESOURCE_ATTRIBUTES` | — | Extra resource attributes, e.g. `service.instance.id=...` per replica. |

Sampling is `1.0` in the app on purpose. Reduce volume in the Collector (tail sampling: keep all errors and slow traces), never in the app, or error traces get lost.

Use the `management.opentelemetry.*` property names — the `management.otlp.tracing.*` / `management.otlp.logging.*` forms are deprecated in Spring Boot 4.

## Local backend

`docker-compose up` starts `lgtm` (`grafana/otel-lgtm`: Collector + Tempo + Loki + Prometheus + Grafana) and points the app at it. Grafana: http://localhost:3000 (anonymous admin — dev only, bound to localhost). To export from `mvn spring-boot:run`, start just `docker-compose up lgtm` and run with `OTEL_EXPORT_ENABLED=true`.

## Request correlation

- Every response carries an `X-Trace-Id` header (exposed via CORS); every `AppErrorResponse` carries `traceId`. Searching that id in Grafana gives the full trace and, from it, its logs.
- `RequestLoggingFilter` runs inside the server span but before Spring Security, so requests rejected by security are logged too. It logs the redacted JSON payload and also attaches it to the server span as `http.request.body`.
- `JwtAuthFilter` tags the server span with `enduser.id` once the caller is authenticated.
- Exceptions handled by `GlobalExceptionHandler`'s 500 handler are recorded on the server span explicitly (error status + exception event) — a handled exception never reaches the observation filter otherwise.

## Rules

- **High-cardinality values (ids, usernames, reference numbers, payloads) go on spans only** — use `addHighCardinalityKeyValue`. Low-cardinality key values become metric tags; a user/company id there explodes metric series.
- **Never put secrets or tokens in telemetry.** Payloads are redacted by `RequestLoggingFilter.SENSITIVE_FIELD_PATTERN`; extend it rather than logging raw bodies elsewhere.
- **Log every business mutation at INFO** in the service layer, in the form `User '{}' <verb> <entity> '{}' (id={})` — see `WarehouseService` or `PurchaseReceiveService`. The trace id is attached automatically; don't add it to messages.
- Telemetry contains data from every company — treat access to the observability backend like `/admin/**`.
