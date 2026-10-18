# Observability (OpenTelemetry)

Norbiz emits all three OpenTelemetry signals — traces, metrics and logs — over OTLP/HTTP. The app only ever talks to an OTLP endpoint (a Collector or an all-in-one backend); which backend stores the data is a deployment concern, not a code one.

## How it's wired

| Concern | Provided by |
|---|---|
| Tracer, OTLP exporters, resource attributes | `spring-boot-starter-opentelemetry` (Micrometer Observation → OTel bridge). **Not** the OTel Java agent — don't add it, it would double-instrument. |
| HTTP server spans | Spring's `ServerHttpObservationFilter` (order `HIGHEST_PRECEDENCE + 1`) |
| Spring Security spans | Spring Security's built-in observations |
| JDBC spans (connection checkout + each query) | `datasource-micrometer-spring-boot`, `jdbc.includes=CONNECTION,QUERY` (prod: `CONNECTION` only). Bind values are never captured; query text is sanitized. |
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
| `TRACING_SAMPLING_PROBABILITY` | `1.0` (prod compose: `0.1`) | `management.tracing.sampling.probability`. |
| `JDBC_SPAN_INCLUDES` | `CONNECTION,QUERY` (prod compose: `CONNECTION`) | `jdbc.includes` — which datasource-micrometer spans are created. |
| `OTEL_RESOURCE_ATTRIBUTES` | — | Extra resource attributes, e.g. `service.instance.id=...` per replica. |

Sampling is `1.0` in the app by default so every transaction is fully visible end-to-end.

### Production sampling

Prod (`deploy/docker-compose.prod.yml`) runs with `TRACING_SAMPLING_PROBABILITY=0.1` and `JDBC_SPAN_INCLUDES=CONNECTION` (no per-query spans): on the VirtualBox VM, full sampling with a span per SQL query made `lgtm` a constant heavy disk writer. Trade-off: head sampling drops ~90% of traces **including error traces** — an `AppErrorResponse.traceId` may have no trace in Tempo. Logs are unaffected (every line still carries `traceId`/`spanId` and is exported to Loki), so search Loki by trace id first. To debug a specific issue, temporarily set `TRACING_SAMPLING_PROBABILITY=1.0` (and `JDBC_SPAN_INCLUDES=CONNECTION,QUERY`) in `~/norbiz/.env` and redeploy.

Use the `management.opentelemetry.*` property names — the `management.otlp.tracing.*` / `management.otlp.logging.*` forms are deprecated in Spring Boot 4.

## Local backend

`docker-compose up` starts `lgtm` (`grafana/otel-lgtm`: Collector + Tempo + Loki + Prometheus + Grafana) and points the app at it. Grafana: http://localhost:3000 (anonymous admin — dev only, bound to localhost). To export from `mvn spring-boot:run`, start just `docker-compose up lgtm` and run with `OTEL_EXPORT_ENABLED=true`.

## Request correlation

- Every response carries an `X-Trace-Id` header (exposed via CORS); every `AppErrorResponse` carries `traceId`. Searching that id in Grafana gives the full trace and, from it, its logs.
- `RequestLoggingFilter` runs inside the server span but before Spring Security, so requests rejected by security are logged too. It logs the redacted JSON payload and also attaches it to the server span as `http.request.body`.
- `JwtAuthFilter` tags the server span with `enduser.id` once the caller is authenticated, and `RequestLoggingFilter` tags it with `norbiz.company.id` from the `X-Company-Id` header (only if numeric; it's the *requested* company — access is still checked by the services). Both also go on the MDC, so every log line of the request carries them (nested `enduser.id` / `norbiz.company.id` in the ECS console JSON; OTLP log attributes, listed explicitly in `ObservabilityConfig`). `RequestLoggingFilter` clears them after the `<--` line. The first `-->` line is logged before authentication, so it has the company but not the user; unauthenticated calls such as `POST /auth/login` have no `enduser.id` (the username is in the logged payload/message).
- Find one user's or tenant's activity regardless of trace sampling — Loki (attributes become structured metadata, dots → underscores): `{service_name="norbiz"} | enduser_id="jdoe"`, `{service_name="norbiz"} | norbiz_company_id="42"`. Tempo (sampled traces only): `{ span.enduser.id = "jdoe" }`, `{ span.norbiz.company.id = "42" }`.
- Exceptions handled by `GlobalExceptionHandler`'s 500 handler are recorded on the server span explicitly (error status + exception event) — a handled exception never reaches the observation filter otherwise.

## Rules

- **High-cardinality values (ids, usernames, reference numbers, payloads) go on spans only** — use `addHighCardinalityKeyValue`. Low-cardinality key values become metric tags; a user/company id there explodes metric series.
- **Never put secrets or tokens in telemetry.** Payloads are redacted by `RequestLoggingFilter.SENSITIVE_FIELD_PATTERN`; extend it rather than logging raw bodies elsewhere.
- **Log every business mutation at INFO** in the service layer, in the form `User '{}' <verb> <entity> '{}' (id={})` — see `WarehouseService` or `PurchaseReceiptService`. The trace id is attached automatically; don't add it to messages.
- Telemetry contains data from every company — treat access to the observability backend like `/admin/**`.
