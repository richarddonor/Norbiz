# Configuration & Database

All runtime config is environment-driven via `src/main/resources/application.properties`:

| Property | Default | Purpose |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/norbiz` | DB connection |
| `spring.jpa.hibernate.ddl-auto` | `update` | Schema management |
| `jwt.secret` | (required) | HMAC-SHA256 key, Base64-encoded |
| `jwt.expiration` | `86400000` | JWT TTL in ms (24 h) |
| `cors.allowed-origins` | `http://localhost:5173` | Frontend origin |
| `app.item-image.upload-dir` | `./item-images` | Image upload path |
| `spring.data.redis.host` / `.port` / `.password` (`REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`) | `localhost` / `6379` / empty | Redis for the query cache (see `docs/CACHING.md`) |
| `spring.data.redis.timeout` / `.connect-timeout` | `200ms` / `500ms` | Kept short so a slow Redis falls back to Postgres instead of slowing requests |
| `app.cache.enabled` (`CACHE_ENABLED`) | `true` | Query cache master switch; tests set it `false` via `src/test/resources/config/application.properties` |
| `app.cache.ttl.lookup` / `.list` (`CACHE_TTL_LOOKUP`, `CACHE_TTL_LIST`) | `10m` / `2m` | Entry TTLs — bound memory only, not freshness |

## Database

`db/init.sql` creates all tables and seeds 49 permissions, 3 roles (`ADMIN`, `SYSTEM_ADMIN`, `SUPER_ADMIN`), and 3 default users (`admin`, `super_admin`, `system_admin`) with BCrypt passwords.

On startup `DataInitializer` also generates any missing default print template per `(company, documentType)` — see `docs/DOCUMENT_TEMPLATES.md`.

`db/drop_all.sql` tears down the entire schema.

`@EnableJpaAuditing` is active; `createdAt`, `updatedAt`, `createdBy`, `updatedBy` are populated automatically on all `Auditable` subclasses via `AuditorAwareImpl`, which reads the current username from `SecurityContext`.
