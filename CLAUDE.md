# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

## Project Architecture

**Norbiz** is a multi-tenant ERP backend: Java 21, Spring Boot 4.0.4, PostgreSQL 17, JWT auth, WAR packaging.

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
- 
- `Company` is the tenant boundary. `Brand`, `Item`, `ItemCategory`, and `Warehouse` are scoped to a company via FK.
- As a general rule, all Entities must belong to only one `Company`

- Entities that are scoped strictly to one company. Update this list everytime there is a new entity:
    Brand, Item, ItemCategory, Employee, Warehouse, Supplier, Customer, InventoryAdjustment
- `InventoryMovement` and `InventoryBalance` are not directly created via their own endpoint (only posted internally by transactions like `InventoryAdjustment`), but are still company-scoped transitively through their `Warehouse`.
- **Company-membership must be verified on every single-record read, not just on list/create/update/delete.** A `GET /{id}` endpoint's `@PreAuthorize("hasAuthority('VIEW_X')")` only checks the permission, not which company the record belongs to — without an explicit check, any user holding that permission could fetch any other company's record by ID (a cross-tenant IDOR). Every company-scoped entity's `findById(id, username)` must resolve the entity, then call the existing `assertCompanyAccess(username, companyId)` helper before returning it — mirror `ItemSkuService.findById` (the original correct example) or `BrandService.findById` (fixed 2026-07-09 after this exact bug was found live in Brand/ItemCategory/Employee/Warehouse/Supplier/Customer/InventoryAdjustment). `update`/`delete` should call this same scoped `findById` rather than checking access a second time separately.
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

### Audit system

Every entity extending `Auditable` (a `@MappedSuperclass`) is automatically logged by `AuditableEntityListener` via JPA lifecycle hooks:

- `@PostLoad` — snapshots entity state into a transient `originalSnapshot` map (reflection-based, scalar fields only, skips `@AuditExclude` fields).
- `@PostPersist` — logs a CREATE action with the full entity snapshot as JSON.
- `@PostUpdate` — diffs current state against the snapshot and logs `{field, oldValue, newValue}` for changed fields only.
- `@PreRemove` — logs a DELETE action with the final snapshot.

`AuditLog` does **not** extend `Auditable` to avoid infinite recursion. `User.password` is annotated `@AuditExclude`. `ApplicationContextHolder` provides static Spring context access because JPA listeners are not Spring-managed beans. `AuditLogController` (`/audit-logs`) is restricted to `SUPER_ADMIN` only.

Inventory ledger/transaction entities (`InventoryMovement`, `InventoryBalance`, `InventoryAdjustment`, `InventoryAdjustmentLine`) deliberately do **not** extend `Auditable` — they are immutable/append-only by design (no update/delete path), so there is nothing to diff over time; the ledger itself already is the audit trail for stock changes. They track `createdAt`/`createdBy` as plain fields instead, set directly by the posting service.

### Entity relationships

```
Company ──< User (many-to-many via user_companies)
Company ──< Brand
Company ──< Employee (optional link to User)
Company ──< Warehouse
Company ──< Supplier
Company ──< Customer (type: CUSTOMER | OUTLET)
Company ──< Item ──< ItemSku
                └──< ItemPrice (one row per PriceType enum value)
                └──> ItemCategory (unique name per company)
Company ──< InventoryAdjustment ──< InventoryAdjustmentLine ──> Item
                                └──> Warehouse
Item + Warehouse ──< InventoryMovement (append-only ledger; posted by InventoryAdjustment and future transactions)
Item + Warehouse ──< InventoryBalance (running quantity/transitQuantity cache, one row per item+warehouse)
User ──< Role (many-to-many) ──< Permission (many-to-many)
AuditLog  (append-only, references entities by type+id strings)
```

`PriceType` enum: `UNIT_PRICE`, `COST_PRICE`, `FOCAL_PRICE`, `MARKDOWN_PRICE`.
Every database constraint (Primary, Foreign, Composite, Unique, etc) should have a explicit name in the Entity class so it will be properly scripted in the database.
For example,
 Foreign Key name = "ITEMS_COMPANY_ID_FK

### Key service patterns

- `PUT /items/{id}` deletes all existing SKUs and prices then re-inserts from the request. `entityManager.flush()` is called before re-insertion to release unique constraints within the same transaction.
- `ItemSku.skuCode` is globally unique across all companies; `Item.itemCode` is unique per company; `Brand.name` is unique per company; `ItemCategory.name` is unique per company.

## Configuration

All runtime config is environment-driven via `src/main/resources/application.properties`:

| Property | Default | Purpose |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/norbiz` | DB connection |
| `spring.jpa.hibernate.ddl-auto` | `update` | Schema management |
| `jwt.secret` | (required) | HMAC-SHA256 key, Base64-encoded |
| `jwt.expiration` | `86400000` | JWT TTL in ms (24 h) |
| `cors.allowed-origins` | `http://localhost:5173` | Frontend origin |
| `app.item-image.upload-dir` | `./item-images` | Image upload path |


## API Call
All API response must implement `AppResponse` dto. In case of an exception, return `AppErrorResponse` instead that contains the error message handled by a `GlobalExceptionHandler`
Make sure that requests are properly validated (Java validation: javax.validation / jakarta.validation) to ensure that required (non-null), min, max, data type enforcement (String, Int, BigDecimal) are checked. Bug me if i do not have these set in API requests
As General rule, Strings must be less than 255 characters

All endpoints that return a list of records should be paginated. Have 50 records per page as default.

## List Filtering & Search
The frontend (sibling repo `Norbiz-Web`, see its `CLAUDE.md`) drives these requirements — check there when a list-page task's backend shape is unclear.
- Column filters must be backend-side, case-insensitive partial-match ("contains") queries, one filter per column, combined with AND.
- Repositories backing a filterable list should extend `JpaSpecificationExecutor<T>` in addition to `JpaRepository<T, Long>`. Compose filters via `com.chardizard.Norbiz.util.SpecificationUtils` (`containsIgnoreCase`, `anyContainsIgnoreCase`, `allOf`) rather than hand-rolling a derived-name/`@Query` method per filter combination — this is the established pattern going forward (see `ItemRepository`/`ItemService` for the initial wiring).
- Date-type columns must support range filtering (`from`/`to` query params resolved to actual dates). The frontend resolves canned ranges (Today, Current Week, Current Month, Last 30 Days, Last 3 Months, Current Year) to concrete dates client-side — the backend only ever receives `from`/`to`, never a range label.
- The frontend's "Global Filter" search box is frontend-only (searches already-fetched page data, including hidden columns) — do **not** build a backend endpoint or query param for it.
- Spreadsheet export (list/report pages) — scope not yet decided: unclear whether export operates only on the currently-fetched page or needs a separate "fetch all matching rows" backend capability. Confirm before implementing.

## Dates
- All 
## Logging
Norbiz should observe the OpenTelemetry specification for logging. Have loggers in all strategic places of the code. Make sure that we are logging the incoming request including the payload. I should be able to see the transaction span from end to finish

## Database

`db/init.sql` creates all tables and seeds 41 permissions, 3 roles (`ADMIN`, `SYSTEM_ADMIN`, `SUPER_ADMIN`), and 3 default users (`admin`, `super_admin`, `system_admin`) with BCrypt passwords.

`db/drop_all.sql` tears down the entire schema.

`@EnableJpaAuditing` is active; `createdAt`, `updatedAt`, `createdBy`, `updatedBy` are populated automatically on all `Auditable` subclasses via `AuditorAwareImpl`, which reads the current username from `SecurityContext`.

## Master Data
Data in Norbiz is divided into two main categories: Master and Transactional. Master data acts as the foundational building block of the system—representing the core entities, places, and things your business interacts with.
Master Data includes Users, Roles, Permissions, Items, Brands, Company, and so on.

## Transactional Data
Transactional Data are data that exist as day-to-day business recordings that utilizes master data. This includes Sales orders, Invoices, Purchases, Inventory movements, Accounting records and so on.

## Users
- Users are the primary actors of Norbiz
- `User` may belong to one or many companies via `user_companies` join table.
- A user can be assigned multiple roles. All permissions across all roles will be unionized in additive fashion granted to the user
- There is a special role `SUPER_ADMIN` that automatically granted ALL permissions
- Services verify the acting user's companies intersect the target resource's company. `SUPER_ADMIN` role bypasses all company checks.
- If a `SUPER_ADMIN` needs to create or update an entity scoped by a company, any controller through its request payload must require it to supply the `Company`id

## Employees
- Employees are people who actually work for the company. 
- Some employees are `Users`. Because some actions are made by people who does not access to Norbiz, their actions are delegated to the `Users`. For example, sales agents in retails outlets
- Employees can be assigned to many tags. Tags are: Agent (Will expand tags more in the future). There is an intermediary table `employee_tags` to enforce zero to many tag relationships

## Items
- Items are goods and services that a company offers and manages
- There are multiple ItemPrice types per Item. Cost price is information sensitive and thus the need for security management. This should not be viewed by users without the correct permission 
- If a user does not have a permission to view the Item's cost price and has access to creating and updating an Item, Norbiz should not allow any changes to the current Cost Price. Set Cost Price to 0 if it is a new Item
- Items can have multiple ItemTags. There is an intermediary table `item_tags` to enforce zero to many tag relationships. 
  Tags are: 
  - INVENTORY: marks that the item inventory physical count is counted and posted during Inventory Movement transactions

## Warehouses
- Warehouses are where the `Items` are stored

## Inventory Management
- Norbiz tracks the inventory level of items throughout different warehouses (storage locations). 
- Different transactions dictate the item count in each warehouse whether it increases or decreases
- Reporting of inventory movement will be tracked by As of Date, within date range or the current count in each warehouse. 
- Inventory Balance report should show the current value of the item per warehouse within a time period. Further drilling this report should forward to the Inventory Ledger report
- The Inventory Ledger report will provide all the transactions that contributed to the Inventory Balance
- Quantity is referred to as main stock level of an item in a warehouse. Transit Quantity is a count that are not yet added or subtracted from the main stock
- Transit Quantity is posted by transactions that have not been fully received or dispatched by Norbiz. Example of this are, purchase ordered items but have not received by the warehouse. 
- Every inventory transaction should commit their Post Transaction Company Id, Warehouse Id, ItemId, Source Type, Reference Number, Sheet Number, Posting Date, Posted By data to the Inventory Movement table

## Transactions
- Transactions are non-master data records. This includes inventory movements, financial transaction, and other executions that makes use of master data
- Always auto-generate a transaction reference number value for each new transaction
- Most transaction will have a sheet number value. Sheet number is based from the control number in the physical document of that transaction
- Always have a Notes column for comments or remarks
Implementation: `TransactionReferenceService.next(companyId, transactionType, prefix)` is the shared mechanism every transaction type should call to satisfy the auto-generated reference number rule — do not hand-roll numbering per transaction type. It's backed by `TransactionSequence` (table `transaction_sequences`, one row per `(company_id, transaction_type)`, locked with `PESSIMISTIC_WRITE` and incremented in its own `REQUIRES_NEW` transaction so the counter still advances if the caller's transaction rolls back — numbers may have gaps but never repeat). Reference numbers are formatted `{PREFIX}-{6-digit zero-padded number}` (e.g. `IA-000001` for Inventory Adjustment) and are unique per `(company_id, reference_number)` on the owning transaction table. `sheetNumber` is plain user input (nullable, not generated) — only capture it on request DTOs, never derive it.

## Purchase Order
- This transaction lets the users buy goods from different Suppliers

## Suppliers
- These are entities where we buy goods or avail services

## Customers
- Customers are entities we sell our goods or Outlets where we consign our products for selling
- There are two Customers: Customers (Direct buyers of products) and Outlets (Branches where we deliver our goods for selling)
- Outlets maintain their own inventory count. So it is important the inventory side-by-side with the warehouses our main warehouse monitor

## Inventory Adjustment
- This is inventory movement transaction to increase or decrease item inventory

## Reports
- Reports special queries that users generate. 
- The main categories are: Inventory, Purchases, Sales (for now)