# Audit System

Every entity extending `Auditable` (a `@MappedSuperclass`) is automatically logged by `AuditableEntityListener` via JPA lifecycle hooks:

- `@PostLoad` — snapshots entity state into a transient `originalSnapshot` map (reflection-based, skips `@AuditExclude` fields) and keeps references to its `@ElementCollection`/`@ManyToMany` collections in `originalCollections`.
- `@PostPersist` — logs a CREATE action with the full entity snapshot as JSON.
- `@PostUpdate` — diffs current state against the snapshot and logs `{field, oldValue, newValue}` for changed fields only, then re-snapshots so a second flush in the same transaction doesn't log the same change twice.
- `@PreRemove` — logs a DELETE action with the final snapshot.

What a snapshot holds:
- Scalars as strings.
- `@ManyToOne`/`@OneToOne` associations as the related record's **id** — read off the proxy without initializing it, since loading anything mid-flush is unsafe. Names are resolved when the history is read.
- `@ElementCollection`/`@ManyToMany` as a **sorted array** of element values (ids for entities). The pre-update contents come from the loaded collection's Hibernate stored snapshot. When a setter replaced a never-loaded collection (e.g. `item.setTags(new HashSet<>(…))`), the old, dereferenced instance is initialized during `@PostUpdate`. That's safe because Hibernate runs collection deletes after entity updates.
- `@OneToMany` children are skipped. They're audited as their own entities, and a field marked `@AuditParent` (e.g. `ItemSku.item`, `ItemPrice.item`) stamps their logs with `parent_type`/`parent_id` so they show up in the parent's history.

Every log also carries `change_set`: one UUID per database transaction (`AuditChangeSet`), so one save of an item and the SKU/price rows it rewrote can be read back as one change. Logs written before this have `change_set`/`parent_*` null, and an association/collection change there shows only as an UPDATE with no recorded fields.

`AuditLog` does **not** extend `Auditable` to avoid infinite recursion. `User.password` is annotated `@AuditExclude`. `ApplicationContextHolder` provides static Spring context access because JPA listeners are not Spring-managed beans. `AuditLogController` (`/audit-logs`) is restricted to `SUPER_ADMIN` only.

Inventory ledger/transaction entities (`InventoryMovement`, `InventoryBalance`, `InventoryAdjustment`, `InventoryAdjustmentLine`) deliberately do **not** extend `Auditable` — they are immutable/append-only by design (no update/delete path), so there is nothing to diff over time; the ledger itself already is the audit trail for stock changes. They track `createdAt`/`createdBy` as plain fields instead, set directly by the posting service.

## Per-record change history (master-data forms)

`GET /master-data/{type}/{id}/history` (`MasterDataHistoryController` → `MasterDataHistoryService`) exposes one record's `audit_logs` rows, newest first, to anyone who can view the record — unlike `/audit-logs`, which stays `SUPER_ADMIN`-only. `type` is a `MasterDataType` (`BRAND`, `ITEM_CATEGORY`, `ITEM_GROUP`, `ITEM`, `ITEM_SKU`, `EMPLOYEE`, `WAREHOUSE`, `SUPPLIER`, `CUSTOMER`, `USER`), which maps to the audited entity's simple class name and its `VIEW_` permission. Authorization runs in the service: that permission (or `SUPER_ADMIN`), then the entity service's scoped `findById(id, username)` for the company check. Adding a type means adding the enum constant and its case in `assertRecordAccess`.

Each entry in the response is one change set: the record's own logs plus its `@AuditParent` parts' logs, keyed by `coalesce(change_set, '#'||id)` and paged by that key. Within an entry:
- The record's own changes are folded (first old value, last new value), with association ids and collection ids resolved to the related record's **current** name (`displayName`/`name`/`skuCode`/`code`/`username`, or `#id (deleted)`).
- Parts are matched by natural key (`PartSpec`: SKUs by `skuCode`, prices by `priceType`), so the delete-and-reinsert an item update does shows only the net ADDED/REMOVED/CHANGED. Money fields are formatted `#,##0.00`.
- `COST_PRICE` rows are dropped unless the caller holds `VIEW_COST_PRICE`.
- `Auditable` bookkeeping fields and `id` are hidden.
- An entry that nets to nothing (an unchanged re-save) is omitted, so a page can come back short — page on `last`.
- `detailed=false` marks entries from before change sets existed.

Adding a new part type means annotating its parent field `@AuditParent` and adding a `PartSpec` in `MasterDataHistoryService`.
