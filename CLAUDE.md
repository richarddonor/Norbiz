# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@docs/TRANSACTIONS.md
Transactional Data specification (transaction types, reference numbering, and the standard transaction document/print layout) lives in `docs/TRANSACTIONS.md`, imported above — see it for anything Purchase Order/Inventory Adjustment/Sales Order-specific rather than duplicating it here. `docs/` is where all non-`CLAUDE.md` project markdown lives going forward — `CLAUDE.md` itself stays at the repo root since Claude Code only auto-discovers it there.

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
    Brand, Item, ItemCategory, Employee, Warehouse, Supplier, Customer, InventoryAdjustment, DocumentTemplate, PurchaseOrder, PurchaseInvoice
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
- `GlobalExceptionHandler` must explicitly catch `org.springframework.security.authorization.AuthorizationDeniedException` and return 403. Spring Security throws this *from inside* the controller invocation whenever a caller lacks the required `@PreAuthorize` authority entirely (as opposed to the company-scoping `SecurityException` case) — with no explicit handler it falls through to the generic `Exception` → 500 handler, which is wrong and was live for every `@PreAuthorize`-protected endpoint until fixed (found while testing `MANAGE_DOCUMENT_TEMPLATES`).

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
Company ──< PurchaseOrder ──< PurchaseOrderLine ──> Item
                          └──> Warehouse (destination — posts Transit Quantity)
                          └──> Supplier (counterparty)
Company ──< PurchaseInvoice ──< PurchaseInvoiceLine ──> Item
                             │                       └──> PurchaseOrderLine (optional — set when PO-based)
                             ├──< PurchaseInvoiceFee
                             ├──> Warehouse
                             ├──> Supplier (counterparty)
                             └──> PurchaseOrder (optional — invoiced-against PO, loaded in full 1:1)
Item + Warehouse ──< InventoryMovement (append-only ledger; posted by InventoryAdjustment, PurchaseOrder, Direct-mode PurchaseInvoice, and future transactions)
Item + Warehouse ──< InventoryBalance (running quantity/transitQuantity cache, one row per item+warehouse)
Company ──< DocumentTemplate (documentType + opaque layout JSON; one default per company+documentType)
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

`db/init.sql` creates all tables and seeds 49 permissions, 3 roles (`ADMIN`, `SYSTEM_ADMIN`, `SUPER_ADMIN`), and 3 default users (`admin`, `super_admin`, `system_admin`) with BCrypt passwords.

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
- Transit Quantity is posted by transactions that have not been fully received or dispatched by Norbiz. Example of this are, purchase ordered items but have not received by the warehouse. Implemented: `PurchaseOrder` posts `transitQuantityDelta = +quantity` per line on creation (leaving `quantity` untouched), and reverses it (`-quantity`) on void — see `docs/TRANSACTIONS.md`. A "Direct" `PurchaseInvoice` (no backing PO) posts the same way; a PO-based `PurchaseInvoice` doesn't — it loads the PO's existing transit posting instead.
- Every inventory transaction should commit their Post Transaction Company Id, Warehouse Id, ItemId, Source Type, Reference Number, Sheet Number, Posting Date, Posted By data to the Inventory Movement table

## Transactions
See `docs/TRANSACTIONS.md` (imported at the top of this file) for the full spec: general transaction rules, reference number generation, per-type details (Inventory Adjustment, Purchase Order, Sales Order), and the standard transaction document/print layout.

## Suppliers
- These are entities where we buy goods or avail services

## Customers
- Customers are entities we sell our goods or Outlets where we consign our products for selling
- There are two Customers: Customers (Direct buyers of products) and Outlets (Branches where we deliver our goods for selling)
- Outlets maintain their own inventory count. So it is important the inventory side-by-side with the warehouses our main warehouse monitor

## Reports
- Reports special queries that users generate. 
- The main categories are: Inventory, Purchases, Sales (for now)

## Document Templates & Printing
Fully frontend-customizable document printing: a designer module (sibling frontend repo) where a user freely positions elements on a page and binds them to backend record fields, plus a print action that renders a real record through the saved layout via the browser's native print (`window.print()` + `@media print` CSS — no PDF library on either side for now).
Follow existing frontend conventions like 
    currency formatting, 
    user display name use instead of username,
    amounts and numbers should be right aligned,
    make sure labels are aligned vertically with one another

**Split of responsibility**: mainly frontend. The backend's role is narrow — persist template layouts (company-scoped, like every other master-data entity) and expose a "what fields can I bind to" schema per document type. The backend does not understand or validate what's inside a layout; it is stored and returned as **opaque JSON** (deliberately exempt from the general "strings under 255 chars" rule — this is structured config, not a display string).

- `DocumentTemplate` extends `Auditable` (it's editable config, not a ledger). Fields: `company` (FK), `documentType` (free-text, e.g. `"INVENTORY_ADJUSTMENT"` — same convention as `InventoryMovement.sourceType`), `name`, `layout` (`TEXT`, opaque JSON), `defaultTemplate` (boolean — **not** `isDefault`, see naming gotcha below), `active`.
- **No seeding mechanism**: neither `db/init.sql` nor `DataInitializer` creates any `DocumentTemplate` rows — don't go looking for one. Every default template that exists (`INVENTORY_ADJUSTMENT`, `PURCHASE_ORDER`, `PURCHASE_INVOICE`, one per company) was created by hand via `POST /document-templates` with `defaultTemplate: true`, mirroring the canonical starting layout in `docs/TRANSACTIONS.md`. A freshly reset DB or a newly created company starts with **zero** templates for every document type until someone POSTs them.
- Only one `defaultTemplate=true` per `(company, documentType)`. Not a DB constraint — enforced in `DocumentTemplateService` by unsetting any other default for that `(company, documentType)` in the same transaction whenever a template is saved with `defaultTemplate=true`.
- **Permission model**: a single global `MANAGE_DOCUMENT_TEMPLATES` gates the entire feature — designing templates *and* the print/preview lookup. Deliberately not per-verb CRUD, not reusing the target document's own VIEW permission (e.g. `VIEW_INVENTORY_ADJUSTMENT`). Matches the flat `MANAGE_SYSTEM` precedent rather than the usual VIEW/CREATE/UPDATE/DELETE-per-entity convention.
- **Bindable-field registry**: `DocumentSchemaRegistry` is a hand-maintained (not reflection-based) static map from `documentType` → header fields + repeating groups (e.g. `INVENTORY_ADJUSTMENT` → `referenceNumber`, `sheetNumber`, `companyName`, `warehouseName`, `adjustmentDate`, `reason`, `createdBy`, plus a repeating group `lines` with `itemCode`/`itemName`/`quantity`). Adding a new document type means adding one registry entry here **and** mirroring it in the frontend's `DOCUMENT_TYPES` array (`DocumentTemplatesPage.tsx`) — the two lists must stay in sync manually, there's no shared source of truth between them yet.
- **Company-scoping gotcha (found live, not a bug)**: the print action's default-template lookup is scoped to the *record being printed*'s own company, not whatever company is currently active in the user's session. A template created while Company A is active only ever applies to Company A's documents. A user managing multiple companies needs a separate default template per `(company, documentType)` pair — switch the active company before creating each one. The frontend's "no template" error message names the missing company explicitly for this reason.

See `docs/TRANSACTIONS.md` for the **standard transaction document layout** (the required base layout every transaction-type template must follow) and the canonical starting-layout JSON to copy when wiring up a new document type.

### Frontend layout JSON (owned entirely by the frontend, backend never inspects it)
```json
{
  "pageSize": "A4", "orientation": "portrait",
  "elements": [
    { "id": "...", "type": "text", "x": 20, "y": 20, "width": 200, "height": 24, "binding": "referenceNumber", "style": { "fontSize": 14, "bold": true } },
    { "id": "...", "type": "static", "x": 20, "y": 50, "width": 100, "height": 20, "text": "Reference #:" },
    { "id": "...", "type": "line", "orientation": "horizontal", "x": 20, "y": 80, "width": 400, "height": 1, "style": { "borderWidth": 1, "borderColor": "#000000" } },
    { "id": "...", "type": "shape", "x": 20, "y": 100, "width": 150, "height": 80, "style": { "borderWidth": 1, "borderColor": "#000000", "fillColor": "transparent" } },
    { "id": "...", "type": "table", "x": 20, "y": 200, "width": 400, "height": 300, "binding": "lines",
      "columns": [ { "binding": "itemCode", "label": "Code", "width": 80 }, { "binding": "itemName", "label": "Item", "width": 200 }, { "binding": "quantity", "label": "Qty", "width": 60 } ] }
  ]
}
```
Five element types: `text` (bound to a single field), `static` (literal label), `table` (bound to a repeating array, one row per item, per-column bindings and per-column adjustable `width` — renders as plain unstyled repeated rows, deliberately **no** table/grid chrome, borders, or header row), `line` (horizontal/vertical rule), `shape` (bordered/filled rectangle for sectioning). `TemplateElementStyle` carries `fontSize`, `bold`, `align`, plus `borderWidth`/`borderColor`/`fillColor` for line/shape.

### Designer (`react-rnd`)
Click a field/shape in the palette to drop it on the canvas at a default position, then drag/resize it via `react-rnd`. One shared `TemplateRenderer` component powers both the designer (edit mode, `Rnd`-wrapped, draggable/resizable) and the print view (read-only, real data substituted in), so positioning/binding logic never drifts between the two. Snap-to-grid is an edit-only aid (`react-rnd`'s `grid` prop + a visual background overlay) — the grid size/on-off state is local designer UI state, never persisted into the saved layout.

**Required Vite config**: `react-rnd`'s bundled `react-draggable` dependency references the Node-only `process` global in a debug-logging guard (`process.env.DRAGGABLE_DEBUG`). Vite doesn't polyfill this in the browser, so without a shim the app throws `ReferenceError: process is not defined` inside React's render phase the instant the first draggable element mounts — an uncaught error that blanks the entire app (no error boundary). The frontend's `vite.config.ts` must keep `define: { 'process.env': {} }` — do not remove it, the designer will not function without it.

### Print flow
`useDocumentPrint` hook fetches the default template for `(companyId, documentType)`, parses its layout, and portals `<TemplateRenderer mode="print">` into an off-screen `#document-print-root` div, then calls `window.print()`. The frontend's global stylesheet has `@media print` rules (`visibility: hidden` on `body *`, overridden back to `visible` for `#document-print-root` and its descendants) that hide all app chrome and show only the printed document — required infrastructure, not page-specific styling.

### Naming gotcha (Lombok + Jackson)
Never name a boolean entity field `isX`. Lombok generates `isX()`/`setX(boolean)` for it (not `getIsX`/`setIsX`), and Jackson serializes that to JSON property `"x"`, not `"isX"` — a request body sending `{"isX": true}` is silently ignored. `DocumentTemplate.defaultTemplate` was renamed from `isDefault` for exactly this reason after it was found live (both templates round-tripped as `"default": false` regardless of what was sent). Use a non-`is`-prefixed name (`active`, `defaultTemplate`, etc.) for every boolean field going forward.