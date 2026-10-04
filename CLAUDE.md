# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository. It intentionally covers only what's cross-cutting and security-critical. Everything else lives in `docs/` and is referenced, not imported, so it's only pulled into context when a task actually touches that area:

- `docs/TRANSACTIONS.md` — Transactional Data (transaction types, reference numbering, standard transaction document/print layout).
- `docs/TRANSACTION_ACTIONS.md` — Transaction history (CREATED/VOIDED) and company-configured actions/sign-offs with prerequisites and per-action roles.
- `docs/DOCUMENT_TEMPLATES.md` — Document Templates & Printing system.
- `docs/AUDIT.md` — Audit logging internals.
- `docs/MASTER_DATA.md` — Users, Employees, Items, Warehouses, Suppliers, Customers.
- `docs/INVENTORY.md` — Inventory Management (ledger/balance/transit quantity model) and Reports.
- `docs/CONFIGURATION.md` — Runtime config properties and database seeding.
- `docs/LIST_FILTERING.md` — Backend contract for filterable/paginated list endpoints.
- `docs/ENTITY_MODEL.md` — Entity relationship diagram and key service implementation patterns.
- `docs/OBSERVABILITY.md` — OpenTelemetry tracing/metrics/logs wiring, config, and telemetry rules.
- `docs/CACHING.md` — Redis query cache for lookup/list endpoints: generation-counter invalidation, key safety rules.

`docs/` is where all non-`CLAUDE.md` project markdown lives — `CLAUDE.md` itself stays at the repo root since Claude Code only auto-discovers it there.

## Build & Run Commands

```bash
# Build (skip tests)
mvn clean package -DskipTests

# Build with tests
mvn clean package

# Run a single test class
mvn test -Dtest=ClassName


# Start full stack (Postgres + app)
docker-compose up --build

# Start only the database
docker-compose up db

# Run app locally (after DB is up)
mvn spring-boot:run

# Reset database
psql -f db/drop_all.sql && psql -f db/init.sql
```

The app runs on port 8080. Swagger UI is at `/swagger-ui.html`.

## Project ArchitectureXO

**Norbiz** is a multi-tenant ERP backend: Java 25, Spring Boot 4.0.4, PostgreSQL 17, JWT auth, WAR packaging.

### Package layout (`com.chardizard.Norbiz`)

| Package | Purpose |
|---|---|
| `models/` | JPA entities |
| `repositories/` | Spring Data JPA interfaces |
| `services/` | Business logic + transactions |
| `controllers/` | REST endpoints (all Swagger-documented) |
| `dto/` | Request/Response POJOs |
| `config/` | Spring Security, CORS, JPA auditing, data seeding |
| `security/` | JWT filter, JWT util, UserDetailsService |
| `audit/` | JPA entity listener, audit log infrastructure |

### Multi-tenancy model

- `Company` is the tenant boundary. `Brand`, `Item`, `ItemCategory`, and `Warehouse` are scoped to a company via FK.
- As a general rule, all Entities must belong to only one `Company`
- Entities that are scoped strictly to one company. Update this list everytime there is a new entity:
    Brand, Item, ItemCategory, ItemGroup, Employee, Warehouse, Supplier, Customer, InventoryAdjustment, DocumentTemplate, PurchaseOrder, PurchaseInvoice, PurchaseReceive, DeliveryReceipt, OutletReceive, TransactionActionDefinition, TransactionEvent
- `InventoryMovement` and `InventoryBalance` are not directly created via their own endpoint (only posted internally by transactions like `InventoryAdjustment`), but are still company-scoped transitively through their `Warehouse`.
- **Company-membership must be verified on every single-record read, not just on list/create/update/delete.** A `GET /{id}` endpoint's `@PreAuthorize("hasAuthority('VIEW_X')")` only checks the permission, not which company the record belongs to — without an explicit check, any user holding that permission could fetch any other company's record by ID (a cross-tenant IDOR). Every company-scoped entity's `findById(id, username)` must resolve the entity, then call the existing `assertCompanyAccess(username, companyId)` helper before returning it — mirror `ItemSkuService.findById` or `BrandService.findById`. `update`/`delete` should call this same scoped `findById` rather than checking access a second time separately.
- Entities that belong to one or more companies. Use an intermediary table like `user_companies` to enforce one to many or many to many relationships. Update this list everytime there is a new entity:
    User
- Entities that are not scoped by `Company` as they are used system-wide:
    Role, Permission

### Authentication & authorization

- Login (`POST /auth/login`) returns a JWT carrying `username`, `displayName`, and `roles` claims.
- `JwtAuthFilter` (OncePerRequestFilter) extracts the Bearer token and populates `SecurityContext` via `UserDetailsServiceImpl`.
- `UserDetailsServiceImpl` loads roles and permissions; both are added as `GrantedAuthority` entries so `@PreAuthorize("hasAuthority('VIEW_ITEM')")` works at the method level.
- RBAC is permission-grained: permissions (e.g. `VIEW_ITEM`, `CREATE_BRAND`) are assigned to roles, roles are assigned to users.
- A user can be assigned multiple roles per Company. Roles are narrow capability-based (e.g. `INVENTORY_VIEWER`, `PRICE_EDITOR`) and composed per user to avoid role explosion.
- Public endpoints: `/auth/**`, `/health`, `/swagger-ui/**`, `/v3/api-docs/**`, `/item-images/**`.
- Admin paths (`/admin/**`) require `SUPER_ADMIN` or `SYSTEM_ADMIN`.
- At application start, `DataInitializer` guarantees that '`SUPER_ADMIN` user and all roles and privileges/permissions are given to it.
- When logging in, if a user belongs to more than one `Company` they need to select the `Company` they wish to login to. The user's actions in that session will be scoped only to that selected `Company`
- Form dropdowns use the slim `/lookups/*` endpoints, gated by `LookupAccess` (entity's VIEW_ permission OR any permission whose form needs it) and always scoped to **one** company (`companyId` param, else the `X-Company-Id` header; required). Adding a transaction type/form that references an entity means adding its permission there — see `docs/LIST_FILTERING.md`.
- `GlobalExceptionHandler` must explicitly catch `org.springframework.security.authorization.AuthorizationDeniedException` and return 403. Spring Security throws this *from inside* the controller invocation whenever a caller lacks the required `@PreAuthorize` authority entirely (as opposed to the company-scoping `SecurityException` case) — with no explicit handler it falls through to the generic `Exception` → 500 handler, which is wrong.

### Audit system

See `docs/AUDIT.md` for how `AuditableEntityListener` hooks into JPA lifecycle events. Key facts worth knowing without opening that doc: every entity extending `Auditable` is automatically logged (CREATE/UPDATE-diff/DELETE); `AuditLog` itself does **not** extend `Auditable` (avoids infinite recursion); `User.password` is `@AuditExclude`; inventory ledger entities (`InventoryMovement`, `InventoryBalance`, `InventoryAdjustment`, `InventoryAdjustmentLine`) deliberately do **not** extend `Auditable` since they're append-only/immutable — the ledger itself is already the audit trail. `TransactionEvent` (transaction history/actions) is likewise append-only and not `Auditable`.

## API Call
All API response must implement `AppResponse` dto. In case of an exception, return `AppErrorResponse` instead that contains the error message handled by a `GlobalExceptionHandler`
Make sure that requests are properly validated (Java validation: javax.validation / jakarta.validation) to ensure that required (non-null), min, max, data type enforcement (String, Int, BigDecimal) are checked. Bug me if i do not have these set in API requests
As General rule, Strings must be less than 255 characters

Deleting a record that other records still reference must surface as `EntityInUseException` (409, `code: "ENTITY_IN_USE"`, `details: {entity, entityId, referencedBy}`) so the frontend can show why the delete failed. In a service `delete`, call `ForeignKeyViolations.deleteOrThrow(repository, entity, "<Entity Label>", id)` instead of `repository.delete(entity)` — it flushes immediately and turns the Postgres FK violation into that exception. Explicit "still in use" pre-checks should throw `EntityInUseException` too, not `IllegalArgumentException`.

All endpoints that return a list of records should be paginated. Have 50 records per page as default. See `docs/LIST_FILTERING.md` for the backend filtering/search contract.

## Gotchas

- **Never name a boolean entity field `isX`.** Lombok generates `isX()`/`setX(boolean)` for it (not `getIsX`/`setIsX`), and Jackson serializes that to JSON property `"x"`, not `"isX"` — a request body sending `{"isX": true}` is silently ignored. Use a non-`is`-prefixed name (`active`, `defaultTemplate`, `voided`, `loaded`, etc.) for every boolean field.

## Logging
Norbiz should observe the OpenTelemetry specification for logging. Have loggers in all strategic places of the code. Make sure that we are logging the incoming request including the payload. I should be able to see the transaction span from end to finish

See `docs/OBSERVABILITY.md`. Key rules: put ids/usernames/payloads on spans as high-cardinality key values, never as metric tags; never put secrets in telemetry; log every service-layer mutation at INFO.

## Caching
Lookup and master-data list endpoints are cached in Redis — see `docs/CACHING.md`. Rules that bite if forgotten:
- A new company-scoped entity without a `getCompany()` must be mapped in `cache/CompanyResolver`, or writes to it won't invalidate anything.
- A cached endpoint's `CacheRegion` must list **every** entity whose fields appear in its response (including names copied from associations).
- Authorization/company-access checks run *before* `QueryCache.page`; anything permission-dependent in the response (e.g. `canViewCostPrice`) goes into the cache params.
- Bulk JPQL/native `UPDATE`/`DELETE` bypass the invalidation listener — bump `GenerationStore` manually.

## Transactions
See `docs/TRANSACTIONS.md` for the full spec: general transaction rules, reference number generation, per-type details (Inventory Adjustment, Purchase Order, Sales Order), and the standard transaction document/print layout.

## Document Templates & Printing
See `docs/DOCUMENT_TEMPLATES.md` for the full spec. Summary: frontend-customizable document printing (designer + native-browser print) where the backend only persists company-scoped template layouts as opaque JSON and exposes a bindable-field schema per document type — it never validates layout contents.
