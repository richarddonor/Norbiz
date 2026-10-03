# Query Cache (Redis)

Lookup (`/lookups/*`) and master-data list endpoints are served through a read-through Redis cache. Code lives in `com.chardizard.Norbiz.cache`.

## What is cached

| Cached | Not cached |
|---|---|
| Every company-scoped `/lookups/*` list, plus users and roles lookups (`LookupService.search` and friends) | `/lookups/*/{id}` (primary-key reads) |
| Master-data lists: brands, item categories, items, item SKUs, warehouses, suppliers, customers, employees, document templates, transaction action definitions, roles, permissions, users | Single-record `GET /{id}` detail endpoints |
| | Transaction lists (PO/PI/PR/adjustments/events), inventory balances, audit logs, anything under `/auth/**` (phase 2 candidates, except audit/auth) |

Only response DTOs are cached, never entities.

## How it works

```
controller/service ── auth + company check ──▶ QueryCache.page(region, scope, params, pageable, type, loader)
                                                  │ key = norbiz:c:v1:{REGION}:sha256(scope | generations | params+paging)
                                                  ├─ hit  → deserialize CachedPage → PageImpl
                                                  └─ miss → loader (DB) → SET key EX ttl
Hibernate write ─▶ CacheInvalidationListener ─▶ (entity, companyId) collected per transaction
                                               └─ afterCompletion(COMMITTED) → INCR norbiz:gen:{Entity}:{companyId} and :all
```

- **`CacheRegion`**: one value per cached endpoint, listing the entities its response depends on. For example, `LOOKUP_PURCHASE_ORDER` depends on PO, PO lines, Item, Supplier and Warehouse, because it embeds item and supplier names.
- **`CacheScope`**: whose rows the result covers.
  - `company(id)` for lookups, using a company that has already been access-checked.
  - `companies(ids)` for a normal user's list, built by `CacheScopeResolver` from their memberships.
  - `global()` for SUPER_ADMIN and for system-wide data.
- **Generation counters (`GenerationStore`)**: a key embeds the current counter value of every entity in the region's dependency list, for every company in scope. A write INCRs the counter, so all older keys become unreachable and expire via TTL. Nothing ever SCANs or deletes keys.
  - `User`, `Role` and `Permission` have only an `:all` counter. A SUPER_ADMIN (global) scope reads the `:all` counters.
- **Invalidation hooks into Hibernate** (post insert/update/delete plus collection events), registered in `CacheConfig`. That way it also catches side-effect writes, such as a Purchase Receive flipping `PurchaseOrder.loaded`, and join-table-only changes such as item tags, user companies and role permissions.
  - Bumps happen only after commit, so a rollback invalidates nothing.
  - A reader that loaded pre-commit data stores it under the old generation, which nobody reads once the bump lands.

## Rules

1. **Check access before calling `QueryCache.page`.** The cache knows nothing about permissions. `LookupService.search` runs `assertCompanyAccess` first, and list endpoints rely on `@PreAuthorize` and a scope built from the caller's own memberships.
2. **Put anything that changes the response into `params`.** That includes permission-dependent shaping such as `canViewCostPrice` (item, PO and PI lookups, and the item list) and every filter.
3. **A region must list every entity whose fields appear in its response.** Missing one means stale data until TTL.
4. **A new company-scoped entity must be resolvable by `CompanyResolver`.** Unmapped entities log a WARN and invalidate nothing. `AuditLog` and `TransactionSequence` are deliberately ignored.
5. **Bulk JPQL/native `UPDATE`/`DELETE` bypass Hibernate events.** Any such query must call `GenerationStore.bump(...)` itself.
6. **Changing a cached DTO incompatibly:** unreadable entries are discarded as misses, but bump `KEY_PREFIX` (`v1` → `v2`) in `QueryCache` to avoid a burst of WARNs after deploy. Cached DTOs need a no-args constructor (or must be records); `QueryCacheTest.cachedDtosRoundTrip` checks this.

## Failure behaviour

Redis is optional at runtime:
- Read or write errors log a WARN and fall back to Postgres.
- Command timeout is 200 ms and connect timeout is 500 ms.
- `management.health.redis.enabled=false` keeps Redis out of the overall health status.
- A failed bump after commit logs a WARN. The affected entries may be stale until their TTL (10 min lookups, 2 min lists).

**Redis must use a `volatile-*` eviction policy** (docker-compose: `volatile-lru`). Generation counters have no TTL, and an evicted counter would restart at 0, which could resurrect an old entry. Persistence is off (`--save ""`), so a restart drops counters and entries together, which is safe.

## Observability

Each cached call is a Micrometer Observation named `norbiz.cache`, which produces both a span and a timer metric:
- Low-cardinality tags: `cache.region` and `cache.result` (`hit` / `miss` / `error`).
- High-cardinality span attribute: `cache.key`.
- Loader DB queries on a miss nest under that span.

Hits and misses are logged at DEBUG (`logging.level.com.chardizard.Norbiz.cache=DEBUG`).

## Inspecting

```bash
docker compose exec redis redis-cli --scan --pattern 'norbiz:gen:*'      # counters
docker compose exec redis redis-cli --scan --pattern 'norbiz:c:v1:LIST_BRAND:*'
docker compose exec redis redis-cli FLUSHDB                               # safe: everything rebuilds from Postgres
```
