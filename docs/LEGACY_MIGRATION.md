# Legacy Migration (jbsKarutora → Norbiz)

One-time migration of the legacy jbsKarutora ERP (SQL Server 2019) into Norbiz. Legacy history is recreated as real Norbiz transactions, not archived: every legacy document becomes the matching Norbiz transaction, and the inventory ledger is rebuilt from those documents. The tooling lives in `tools/legacy-migration/`.

The run is **repeatable**. It always starts by wiping Norbiz down to its seeded basics, so a rehearsal and the real cutover are the same command against a freshly restored legacy backup.

## Running it

```bash
cd tools/legacy-migration
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cp config.env.example config.env        # fill in (git-ignored)

./run_migration.sh --confirm-reset <target db name>
```

`run_migration.sh` is the one command for rehearsals and the real cutover. It runs five stages and stops at the first failure:

1. **Preflight.** Checks that:
   - the target database is reachable and its name matches `--confirm-reset`;
   - **the Norbiz app is not connected** (no JDBC sessions);
   - the schema and seed data exist;
   - the legacy SQL Server database is reachable.
2. **Backup.** Always takes a `pg_dump` of the target to `backups/<db>-<timestamp>.dump`. It uses `pg_dump` if it's on PATH, else `docker exec $PG_DOCKER_CONTAINER pg_dump`. With neither, it refuses to continue.
3. **Migrate** (`migrate.py`): extract, reset, load, reconcile, report. The run stops if a report check fails.
4. **Item pictures** (`images.py --clean`).
5. **Flush Redis**, the query cache.

The console output is also written to `reports/run-<timestamp>.log`, next to the report.

Options:
- `--skip-extract` reuses the previous extract.
- `--skip-images` skips stage 4.
- `--images-limit N` copies only N pictures, for quick rehearsals.

The steps can also be run on their own: `migrate.py --confirm-reset <db>` and `images.py`.

The target database needs the Norbiz schema and seed data before the first run. Start the app against it once and let `DataInitializer` finish. After a run, start the app again: `DataInitializer` re-adds anything missing, recreates the empty default "Norbiz" company and generates print templates for the migrated company. The loader links `super_admin` to the migrated company.

**Wait for seeding to finish** before stopping a freshly started app. `DataInitializer` runs *after* the "Started NorbizApplication" log line. Poll for the `super_admin` user instead of relying on that line.

Configuration (`config.env`) is plain environment variables:

| Variable | Default |
|---|---|
| `MSSQL_HOST` / `MSSQL_PORT` | `localhost` / `1433` |
| `MSSQL_USER` / `MSSQL_PASSWORD` | `sa` / *(empty)* |
| `MSSQL_DATABASE` | `jbsKarutora` |
| `PG_DSN` | `postgresql://norbiz:changeme@localhost:5432/norbiz_mig` |
| `ITEM_IMAGE_UPLOAD_DIR` | `~/norbiz`. Must be the Norbiz server's `app.item-image.upload-dir`. |
| `PG_DOCKER_CONTAINER` | *(none)*. The Postgres container used for the backup when `pg_dump` isn't installed. |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / *(empty)*, the same Redis the app uses. |

## Cutover day

1. Freeze the legacy system: no more postings.
2. Take a fresh SQL Server backup and restore it as `jbsKarutora` on the SQL Server in `config.env`.
3. Stop Norbiz.
4. Run `./run_migration.sh --confirm-reset <production db>` (about 10 min, plus 20–40 min for pictures).
5. Review `reports/migration-<timestamp>.txt`. Every check must PASS. Read the Issues and transit sections.
6. Start Norbiz and wait for `DataInitializer`.
7. Reset the migrated users' passwords and assign roles.
8. Spot-check:
   - a few migrated documents and an outlet's stock card against legacy;
   - that a new Delivery Receipt continues the legacy numbering.
9. Go live.

If anything is wrong, restore the backup the run took (`pg_restore --clean --if-exists -d <db> backups/<file>.dump`), fix the cause, and run again. Rehearse on a copy (e.g. `norbiz_mig`) with an earlier legacy backup first.

## Pipeline

| Step | File | What it does |
|---|---|---|
| extract | `extract.py` | Copies the 44 needed legacy tables as-is into a fresh `legacy` schema in the target database (picture columns skipped; `tblInventory` only non-zero rows). |
| 00 | `sql/00_prepare.sql` | Indexes the legacy copy. |
| 01 | `sql/01_reset_data.sql` | Truncates every Norbiz table except permissions, roles, role_permissions and the seeded users (admin, super_admin, system_admin). |
| 10 | `sql/10_master.sql` | Company, users, employees, catalog, suppliers, customers + outlet warehouses, pull out reasons, bills of materials. |
| 20 | `sql/20_documents.sql` | All transactions: headers, lines, loaded quantities, reconstructed documents. |
| 25 | `match_returns.py` | Links each Outlet Delivery Return to the sale it reverses. |
| 40 | `sql/40_ledger.sql` | Inventory movements, balances and CREATED/VOIDED history, generated from the documents. |
| 50 | `sql/50_reconcile.sql` | Aligns on-hand with the legacy stock balance. |
| 60 | `sql/60_finalize.sql` | Identity sequences, reference numbering, audit marker. |
| report | `report.py` | Counts, reconstructed documents, fixes, balance reconciliation, hard checks. |
| images | `images.py` | Item pictures into the item-image store. |

The loader writes with set-based SQL, not through the services. The "on-hand can't go below zero" rule therefore doesn't apply during the load (legacy carries negative balances). It applies as normal once the app runs. Each step writes rows exactly as the matching Norbiz service would. After a load, Redis must be flushed: bulk SQL bypasses cache invalidation.

## Mapping

| Legacy | Norbiz |
|---|---|
| `tblCompany` | a new `companies` row |
| `tblWareHouses` (ID 1) | main warehouse, keeps ID 1 |
| `tblCustomers` | `customers` (keep IDs). `OUTLET` when legacy typed it so **or it held stock**. Every outlet gets a warehouse with the customer's ID, because legacy keyed outlet stock by customer ID. |
| `tblSecurityUsers` | `users`, linked to the company, no roles, and a password that can't match. **An admin must reset each password** and assign roles (legacy security groups don't map 1:1 to Norbiz roles). |
| `tblEmployees` | `employees` (keep IDs). Every ODR agent is tagged `AGENT`. |
| `tblItems` | `items` (keep IDs) + the 4 `item_prices` + `INVENTORY` tag from `IInventory` |
| `tblItemDescriptions` | `item_categories` (by distinct name, plus `UNCATEGORIZED`) |
| `tblCategoryChartsOrig` | `item_groups` (the code in parentheses becomes BN initials) |
| `tblBrands` | `brands` |
| `tblSMSKUs`, `tblItems.SMSKUNo/ImonoSKUNo` | `item_skus`: every SKU each item carries. Legacy shares one SM SKU code across many items (≈47.6k items, ≈11.9k codes), and Norbiz allows that, since codes are unique per item only. Price = the SKU master's price for the code, else the item's unit price. |
| `tblSuppliers` | `suppliers` (keep IDs) |
| `tblPullOutReasons`, `tblBillofMaterial`+`RawDetail` | `pull_out_reasons`, `bills_of_materials` |
| `tblInventoryAdjustment` / `tblOutletInventoryAdjustment` | Inventory Adjustment (outlet ones: header ID +100,000; main lines: ID +3,000,000) |
| `tblStockTransfer` | Stock Transfer. `DRId` becomes the DR's `stock_transfer_id`. |
| `tblDeliveryReceipts` | Delivery Receipt |
| `tblOutletReceives` | Outlet Receive |
| `tblOutletDeliveryReceipts` | Outlet Delivery Receipt |
| `tblOutletDeliveryReturns` | Outlet Delivery Return |
| `tblReturnSlips` | Outlet Pull Out |
| `tblOutletPullOut` | Pull Out Receive |
| `tblSupplierInvoices` | Direct Purchase Invoice |
| `tblItemReceive` | Purchase Receive |
| `tblAssembly` + `Detail` + `RawMaterial` | Assembly (raw material lines: ID +1,000) |

The legacy ledger (`tblTransactions`, `tblTransactionDetails`) is not migrated as documents. It is only used to classify how each legacy document posted (e.g. a pull out that went through transit vs straight to on-hand) and to reconcile the result.

Every migrated document keeps its legacy reference and sheet number and carries `origin = MIGRATED`. Norbiz numbering continues after the highest legacy number for each prefix legacy shared (DR, OR, ODR, ODRR, STF, DRR, OPO, ASM), so the next DR after `DR-093121` is `DR-093122`.

## Rules

- **Voided** legacy documents are migrated voided and post no movements. Legacy removed a voided document's ledger rows, and a create + void pair would net to zero.
- **Never-posted documents** are migrated **voided**, with remarks saying why. This covers unposted (draft) Outlet Receives and supplier invoices that never touched stock and were never received (pre-inventory, 2019–Feb 2023). They had no stock effect in legacy either.
- **Reconstructed documents** (`origin = RECONSTRUCTED`, `RCN-<prefix>-000001`, remarks name the legacy source) fill steps the legacy flow skipped:
  - **Pull Out Receive:** for each pull out legacy posted straight into main on-hand.
  - **Purchase Receive:** for each supplier invoice legacy posted straight into on-hand.
  - **Zero-price Outlet Delivery Receipt:** for return lines no sale could account for.
  - **Inventory Adjustment, over-receiving:** for stock a receive took beyond what its source still had outstanding (legacy allowed this).
  - **Inventory Adjustment, cutover correction:** one per warehouse, aligning on-hand with the legacy balance at cutover.
- **Receive allocation:** a receive line loads its source line only up to what is still outstanding, in date order. The excess becomes the reconstructed adjustment above.
- **Splits:** a Norbiz receive has one source and a return reverses one sale. A legacy receive spanning several sources, or a return spanning several sales, is split into one document per source (`<ref>-2`, `<ref>-3`, …).
- **Return matching** (`match_returns.py`):
  - Returns are processed in date order. Each one is matched to the most recent non-voided sale of the same outlet, on or before the return, that covers the most of its lines with unreturned quantity.
  - Lines are covered whole. Within a sale, quantity is taken from its lines oldest first, as the service does.
  - Unit price and agent come from the sale.
- **Prices:** line unit price = legacy net amount ÷ quantity (legacy discounts folded in), so document totals match legacy.
- **Dates:**
  - Business dates become the UTC midnight of the legacy calendar day.
  - Impossible dates (before 2000, or more than a year past cutover) fall back to the creation date, then the cutover.
  - Audit timestamps are read as Asia/Manila local time.
- **Authors:** legacy `CreatedByID`/`VoidedBy` hold employee IDs. They become the employee's login, else `legacy-<employee code>`, else `legacy-migration`.
- **Codes:**
  - Blank codes become `LEGACY-<id>` / `C-<id>` / `S-<id>`.
  - Duplicate codes keep the oldest owner; later duplicates get `-<id>` appended.
  - Duplicate supplier-invoice and receive numbers are renamed the same way.
- **Audit:** bulk inserts don't fire `AuditableEntityListener`, so migrated master data has no change history before cutover. One `audit_logs` marker on the company records the load. Migrated rows carry `created_by = legacy-migration` (or the legacy author).
- **Columns nullable only for legacy:** some FKs are nullable just so legacy rows without them fit (e.g. `OutletPullOut.pullOutReason`). The API still requires them.

Every fix is logged in `migration.issues` (kind, entity, legacy ID, detail) and summarised by the report.

## Reconciliation

The documents reproduce the legacy ledger (`tblTransactionDetails`) almost exactly; the only differences come from the reconstructed documents. Legacy's stored balances (`tblInventory`) disagree with its own ledger for a few thousand outlet item/warehouse pairs. `tblInventory` is what legacy users see as stock, so step 50 aligns on-hand to it.

**Transit** differences can't be corrected by an adjustment. They are reported, not fixed (about 1.4k pairs, 3.3k units, all legacy-internal inconsistencies).

Report checks, any failure fails the run:
- Document counts per type equal legacy − skipped + split parts.
- Every return line is linked to a sale.
- On-hand equals the legacy balance.
- Balances equal the sum of movements.
- No line is loaded beyond its quantity.

## Known limitations

- **Payables:** supplier invoice payment status is derived from legacy amount paid. Payments themselves aren't migrated (Norbiz has no supplier payments module yet).
- **Customer and supplier details** (address, TIN, terms, credit limit) and item units, colour and size have no Norbiz fields and aren't migrated.
- **Unmigratable documents:** receives with no lines, and receives whose only lines name items not on their source, can't be migrated as documents. They are logged as skipped; any stock they moved is carried by reconstructed adjustments.
