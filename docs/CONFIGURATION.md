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

## Database

`db/init.sql` creates all tables and seeds 49 permissions, 3 roles (`ADMIN`, `SYSTEM_ADMIN`, `SUPER_ADMIN`), and 3 default users (`admin`, `super_admin`, `system_admin`) with BCrypt passwords.

`db/drop_all.sql` tears down the entire schema.

`@EnableJpaAuditing` is active; `createdAt`, `updatedAt`, `createdBy`, `updatedBy` are populated automatically on all `Auditable` subclasses via `AuditorAwareImpl`, which reads the current username from `SecurityContext`.
