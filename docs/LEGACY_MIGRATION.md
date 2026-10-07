# Legacy Migration (jbsKarutora → Norbiz)

One-time migration of master data and transaction history from the legacy **jbsKarutora** system (SQL Server) into Norbiz (Postgres). The tooling lives in `tools/legacy-migration/`, outside the Spring app. It never goes through the REST API or the service layer.

## Status

| Piece | State |
|---|---|
| `extract.py`: copy the raw legacy tables into a `legacy` schema | **Built** |
| `sql/00_prepare.sql`: index `legacy.*`, create the `migration` working schema | **Built** |
| `origin` column on every transaction header, plus `TransactionOrigin` enum, list/report filter | **Built** (app side) |
| Legacy-only transaction types (Stock Transfer, Outlet Pull Out, Pull Out Receive, Assembly) | **Built** (app side), see `docs/TRANSACTIONS.md` |
| `sql/01+`: transform `legacy.*` into Norbiz tables, post movements and balances | **Not written yet** |
| Reconciliation against `tblInventory` | **Not written yet** |
| `images.py` (item pictures) and `config.env.example` | **Referenced by `extract.py` but missing** |

Everything below the "Load" heading describes the contract the rest of the codebase already assumes. Treat it as the spec for the unwritten loader steps.

## Pipeline

```
SQL Server (jbsKarutora backup)
   │  extract.py — as-is copy, no transformation
   ▼
Postgres schema `legacy`      (dropped + recreated every extract)
   │  sql/00_prepare.sql — indexes, ANALYZE, fresh `migration` schema
   ▼
Postgres schema `migration`   (working tables: id maps, staging)
   │  sql/01+ — set-based INSERTs into Norbiz tables          ← not written yet
   ▼
Norbiz tables (public schema) → reconcile against legacy.tblinventory
```

Run it against a **scratch database** (default `norbiz_mig`), never a live one. Both the `legacy` and `migration` schemas are disposable and rebuilt on every run.

## 1. Extract (`extract.py`)

- Connects to SQL Server with `pymssql` and to Postgres with `psycopg` 3. Dependencies are in `tools/legacy-migration/.venv`.
- Each run drops and recreates `legacy`, so it always mirrors whichever backup is currently restored.
- Copies each table in its `TABLES` dict as-is: column names are lower-cased, types are mapped via `PG_TYPES`, and data is loaded with `COPY` in 20k-row batches. Any unmapped SQL Server type aborts the run instead of guessing.
- Skips binary columns (`image`, `varbinary`, `binary`, `timestamp`). Item pictures are meant to come from a separate `images.py`.
- Strips NUL characters from strings, since Postgres `text` can't store them.
- Only extracts the non-zero rows of `tblInventory` (`Quantity <> 0 OR IntransitQty <> 0`). The table has about 75M rows, almost all zero.

Configuration comes from environment variables:

| Variable | Default |
|---|---|
| `MSSQL_HOST` / `MSSQL_PORT` | `localhost` / `1433` |
| `MSSQL_USER` / `MSSQL_PASSWORD` | `sa` / *(empty)* |
| `MSSQL_DATABASE` | `jbsKarutora` |
| `PG_DSN` | `postgresql://norbiz:changeme@localhost:5432/norbiz_mig` |

```bash
cd tools/legacy-migration
.venv/bin/python extract.py
psql "$PG_DSN" -f sql/00_prepare.sql
```

## 2. Prepare (`sql/00_prepare.sql`)

- Adds indexes on the detail→master join columns (`masterid`, `drid`, `odrid`, `returnslipmasterid`, `ancdetailid`, …) and on the legacy ledger (`tbltransactions`, `tbltransactiondetails`), then runs `ANALYZE`.
- Drops and recreates the `migration` schema for working tables. Must run after every extract, because the extract drops the indexed tables.

## 3. Load (not written yet)

### Legacy table → Norbiz mapping

Mappings marked *(confirm)* are inferred from table names and reference prefixes and haven't been checked against the data yet.

**Master data**

| Legacy | Norbiz |
|---|---|
| `tblCompany` | `Company` |
| `tblWareHouses` | `Warehouse` (one marked `main` per company; outlet warehouses linked via `Customer.warehouse`) |
| `tblSecurityUsers` | `User` + `user_companies` *(confirm password handling: users will likely need a reset)* |
| `tblEmployees` | `Employee` (agents tagged `AGENT`) |
| `tblBrands` | `Brand` |
| `tblCategoryChartsOrig` | `ItemCategory` |
| `tblItems`, `tblItemDescriptions`, `tblSMSKUs` | `Item` / `ItemSku` *(confirm split)* |
| `tblSuppliers` | `Supplier` |
| `tblCustomers`, `tblCustomerTypes` | `Customer` (`type = OUTLET` for outlets) |
| `tblPullOutReasons` | `PullOutReason` |
| `tblBillofMaterial`, `tblBillOfMaterialRawDetail` | `BillOfMaterial` |

**Transactions** (headers + details)

| Legacy | Norbiz type | Notes |
|---|---|---|
| `tblInventoryAdjustment` | Inventory Adjustment | |
| `tblOutletInventoryAdjustment` | Inventory Adjustment (outlet's warehouse) | *(confirm)* |
| `tblStockTransfer` | Stock Transfer (`STF`) | `drid` links the delivering DR |
| `tblDeliveryReceipts` | Delivery Receipt | |
| `tblOutletReceives` | Outlet Receive | `ancdetailid` → DR line |
| `tblOutletDeliveryReceipts` | Outlet Delivery Receipt | |
| `tblOutletDeliveryReturns` | Outlet Delivery Return | details keyed by `returnslipmasterid` *(confirm)* |
| `tblReturnSlips` | Outlet Pull Out (`DRR`) | *(confirm)* |
| `tblOutletPullOut` | Pull Out Receive (`OPO`) | `ancdetailid` → pull-out line *(confirm)* |
| `tblSupplierInvoices` | Purchase Invoice (Direct) | no PO table is extracted, so POs aren't migrated |
| `tblItemReceive` | Purchase Receive | |
| `tblAssembly`, `tblAssemblyDetail`, `tblAssemblyRawMaterial` | Assembly | outputs / raw materials → `AssemblyLine.kind` |

The legacy ledger (`tblTransactionTypes`, `tblTransactions`, `tblTransactionDetails`) isn't migrated as documents. It's used to classify legacy documents and to work out which movements each one actually posted.

### Origin

Every transaction header has `origin` (`TransactionOrigin`, column default `'NATIVE'`). Only the loader writes anything other than `NATIVE`:

- **`MIGRATED`**: a legacy document copied 1:1. It **keeps its legacy reference number**, so legacy prefixes (`STF`, `DRR`, `OPO`, …) were kept in Norbiz to let numbering continue.
- **`RECONSTRUCTED`**: a document that didn't exist in legacy, created to complete a flow the legacy data skipped. Example: a Pull Out Receive for a pull out that went straight to on-hand. Its `remarks` must name the legacy source document.

Services never set `origin` and request DTOs never accept it. List endpoints and detailed reports filter by it (see `docs/TRANSACTIONS.md` → "Origin").

### Loader rules

- **Set-based SQL, not the API.** Movements (`inventory_movements`) and balances (`inventory_balances`) are written directly. Each migrated line posts the same `quantityDelta` / `transitQuantityDelta` / `sourceType` its Norbiz transaction type would (see `docs/INVENTORY.md` and `docs/TRANSACTIONS.md`), so ledger reports read the same as for native data.
- **The negative-stock rule doesn't apply during the load.** That rule lives in `InventoryStockService`, which the loader bypasses, and legacy history contains negative balances. Load it as-is; don't "fix" history to satisfy the rule. Once the load is done, every API posting is subject to the rule as normal. Any balance that ends up negative can only be moved toward zero, since an adjustment *adding* stock is always allowed (see `docs/INVENTORY.md` → "Negative stock").
- **Reference sequences.** After loading, set each `transaction_sequences.last_number` to the highest migrated number per (company, transaction type), so `TransactionReferenceService` continues from there instead of reissuing legacy numbers.
- **Caches.** Bulk SQL bypasses the cache invalidation listener. Bump `GenerationStore` for every affected region, or flush Redis, after loading (see `docs/CACHING.md`).
- **Audit.** Bulk inserts don't fire `AuditableEntityListener`, so master data arrives with no audit history. That's expected. Set `created_by` to a recognizable migration user.
- **Transaction history.** Record a `CREATED` (and, for voided legacy documents, `VOIDED`) `TransactionEvent` per migrated header so the history panel isn't empty. `db/backfill_transaction_events.sql` shows the pattern.
- **Nullable-for-legacy columns.** Some FKs are nullable only so legacy rows without them fit (e.g. `OutletPullOut.pullOutReason`). The API still requires them.

## 4. Reconcile (not written yet)

After loading, compare Norbiz `inventory_balances` (quantity, transit) per item and warehouse against `legacy.tblinventory` (`Quantity`, `IntransitQty`) and list every mismatch. A mismatch means a mapping or `sourceType` error in the loader (or a gap that needs a `RECONSTRUCTED` document), not something to fix with an adjustment.
