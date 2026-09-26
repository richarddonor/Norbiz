# Audit System

Every entity extending `Auditable` (a `@MappedSuperclass`) is automatically logged by `AuditableEntityListener` via JPA lifecycle hooks:

- `@PostLoad` — snapshots entity state into a transient `originalSnapshot` map (reflection-based, scalar fields only, skips `@AuditExclude` fields).
- `@PostPersist` — logs a CREATE action with the full entity snapshot as JSON.
- `@PostUpdate` — diffs current state against the snapshot and logs `{field, oldValue, newValue}` for changed fields only.
- `@PreRemove` — logs a DELETE action with the final snapshot.

`AuditLog` does **not** extend `Auditable` to avoid infinite recursion. `User.password` is annotated `@AuditExclude`. `ApplicationContextHolder` provides static Spring context access because JPA listeners are not Spring-managed beans. `AuditLogController` (`/audit-logs`) is restricted to `SUPER_ADMIN` only.

Inventory ledger/transaction entities (`InventoryMovement`, `InventoryBalance`, `InventoryAdjustment`, `InventoryAdjustmentLine`) deliberately do **not** extend `Auditable` — they are immutable/append-only by design (no update/delete path), so there is nothing to diff over time; the ledger itself already is the audit trail for stock changes. They track `createdAt`/`createdBy` as plain fields instead, set directly by the posting service.
