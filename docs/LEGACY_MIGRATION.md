# Legacy Migration (jbs* → Norbiz)

Migration of the legacy Jeonsoft ERP databases (SQL Server 2019) into Norbiz, **one legacy database per Norbiz company**. Legacy history is recreated as real Norbiz transactions, not archived: every legacy document becomes the matching Norbiz transaction, the inventory ledger is rebuilt from those documents, and (where legacy used it) the AR/AP subledger is rebuilt from legacy's payments and applications. The tooling lives in `tools/legacy-migration/`.

| Legacy database | Norbiz company | Business | Status |
|---|---|---|---|
| `jbsKarutora` | DMARK MULTISALES INC. - KARUTORA | Jewellery, consignment to outlets (stock transfers, DR → outlet receipt → outlet sales) | Live since the 2026-10-19 cutover |
| `jbsNeostrata` | DMARK MULTISALES INC. - NEOSTRATA | Skincare, direct sales from one warehouse (sales invoices, returns, collections, deposits) | Rehearsed 2026-10-23; not loaded into prod yet |

Both databases share one legacy schema, but Neostrata is on older legacy updates: it lacks `tblAssembly*`, `tblBillofMaterial*`, `tblOutletPullOut*` and `tblStockTransferUserActions` and a few columns. Schema decisions follow Karutora (the first migrated company). `extract.py` creates whatever a database lacks as empty tables/NULL columns (from `legacy_columns.json`), so the same SQL runs against both.

The run is **repeatable and company-scoped**. It starts by wiping the company *this* legacy database loads into (its previous load), and nothing else. A rehearsal and the real cutover are the same command against a freshly restored legacy backup, and loading one company never touches another.

## Running it

```bash
cd tools/legacy-migration
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cp config.env.example config.env        # fill in (git-ignored)

./run_migration.sh --confirm-reset <target db name> --legacy-db jbsNeostrata     # or jbsKarutora
```

`run_migration.sh` is the one command for rehearsals and the real cutover. `--legacy-db` picks the SQL Server database (default: `MSSQL_DATABASE` from the config); `--config FILE` uses another config file. It runs five stages and stops at the first failure:

1. **Preflight.** Checks that:
   - the target database is reachable and its name matches `--confirm-reset`;
   - **the Norbiz app is not connected** (no JDBC sessions);
   - the schema and seed data exist;
   - the legacy SQL Server database is reachable.
2. **Backup.** Always takes a `pg_dump` of the target to `backups/<db>-<timestamp>.dump`. It uses `pg_dump` if it's on PATH, else `docker exec $PG_DOCKER_CONTAINER pg_dump` when `PG_DSN` points at localhost, else a throwaway `docker run postgres:17-alpine pg_dump` against `PG_DSN` (remote targets). With neither pg_dump nor docker, it refuses to continue.
3. **Migrate** (`migrate.py`): extract, wipe this company's previous load, load, reconcile, report. The run stops if a report check fails.
4. **Item pictures** (`images.py --clean`).
5. **Flush Redis**, the query cache.

The console output is also written to `reports/run-<timestamp>.log`, next to the report.

Options:
- `--skip-extract` reuses the previous extract.
- `--skip-images` skips stage 4.
- `--images-limit N` copies only N pictures, for quick rehearsals.

The steps can also be run on their own: `migrate.py --confirm-reset <db>` and `images.py`.

The target database needs the Norbiz schema and seed data before the first run. Start the app against it once and let `DataInitializer` finish. After a run, start the app again: `DataInitializer` re-adds anything missing and generates print templates for a newly migrated company. The loader links the seeded users (`super_admin`, `system_admin`, `admin`) to the migrated company, since login requires at least one company.

### Several companies in one database

- **Which company.** A legacy database loads into the Norbiz company whose name equals its `tblCompany.Name` (e.g. "DMARK MULTISALES INC. - NEOSTRATA"). The first run creates it. Don't rename a migrated company before its final load, or the next run creates a second one.
- **What a re-run wipes** (`sql/01_reset_data.sql`): every row of that company in every company-scoped table, and the child rows left pointing at them (an FK sweep, so tables added later are covered). Also its audit logs, the migration marker, and the users only that company had who were created by a previous load. Users who belong to another company keep their accounts and only lose this company's membership (re-linked by the load). **Kept:** the company row (name, theme, logo), its print templates, and accounting settings set in the app.
- **IDs.** The loader keeps legacy IDs as Norbiz IDs, and the companies' legacy IDs overlap. Step 01 therefore shifts every ID column of the legacy copy by an **offset** (`migration.ctx.id_offset`). It is 0 when nothing else is loaded, which reproduces the original single-company load exactly. Otherwise it is the next multiple of 1,000,000 above the highest ID of any table (Neostrata on prod: 7,000,000), so migrated rows can't collide with other companies' rows or with future native ones. Reports print legacy IDs (`migration.legacy_id(id)`); generated codes such as `LEGACY-<id>` or `C-<id>` use the legacy ID.
- **Users** are shared across companies. A legacy login that already exists is the **same person** when the names agree, i.e. the legacy last name is in the existing display name, or with no last name the display name starts with the first name. That user is linked to the new company (Neostrata: `Paul`, `flor`, `Chris`). Otherwise the login gets the company's last word as a suffix (Neostrata: `joan` → `joan-neostrata`). Both cases are logged (`linked` / `renamed` issues).
- **Re-running a live company** wipes everything it posted natively since its cutover. For Karutora (live), only do that in a rehearsal copy.

**Wait for seeding to finish** before stopping a freshly started app. `DataInitializer` runs *after* the "Started NorbizApplication" log line. Poll for the `super_admin` user instead of relying on that line.

Configuration (`config.env`) is plain environment variables:

| Variable | Default |
|---|---|
| `MSSQL_HOST` / `MSSQL_PORT` | `localhost` / `1433` |
| `MSSQL_USER` / `MSSQL_PASSWORD` | `sa` / *(empty)* |
| `MSSQL_DATABASE` | `jbsKarutora` |
| `PG_DSN` | `postgresql://norbiz:changeme@localhost:5432/norbiz_mig` |
| `ITEM_IMAGE_UPLOAD_DIR` | `~/norbiz`. Must be the Norbiz server's `app.item-image.upload-dir`. |
| `ITEM_IMAGE_REMOTE` | *(none)*. For a Norbiz server on another machine: `<ssh user@host>:<Docker volume>` (prod: `<user>@10.18.18.18:norbiz_item_images`, the volume mounted at the app's upload dir). `images.py` streams the pictures over SSH as one tar straight into that volume, owned by the app user; nothing is written locally. Takes precedence over `ITEM_IMAGE_UPLOAD_DIR`. |
| `PG_DOCKER_CONTAINER` | *(none)*. The Postgres container used for the backup when `pg_dump` isn't installed and `PG_DSN` is localhost; ignored for remote targets. |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / *(empty)*, the same Redis the app uses. |

## Cutover day

The same runbook serves every company. Loading a second company into the live production database means a Norbiz **downtime for every company** (the app must be stopped during the load): Neostrata takes about 5 minutes plus pictures.

1. Freeze the legacy system: no more postings.
2. Take a fresh SQL Server backup and restore it under its own name (`jbsKarutora`, `jbsNeostrata`) on the SQL Server in `config.env` (prod: `mssql2019` on 10.18.18.18).
3. Stop Norbiz.
4. Run `./run_migration.sh --confirm-reset <production db> --legacy-db <legacy db>` (Karutora: about 10 min, plus 20–40 min for pictures; Neostrata: about 5 min).
5. Review `reports/migration-<timestamp>.txt`. Every check must PASS. Read the Issues and transit sections.
6. Start Norbiz and wait for `DataInitializer`.
7. Reset the migrated users' passwords and assign roles. Set the real allowed roles on the migrated Stock Transfer actions (Transaction Actions screen); they allow only SUPER_ADMIN until then. A company with AR/AP history (Neostrata) has its accounting start date set to its earliest document; check it on the Accounting Settings screen.
8. Spot-check:
   - a few migrated documents and an outlet's stock card against legacy;
   - that a new Delivery Receipt continues the legacy numbering.
9. Go live.

If anything is wrong, restore the backup the run took (`pg_restore --clean --if-exists -d <db> backups/<file>.dump`), fix the cause, and run again. Rehearse on a copy (e.g. `norbiz_mig`) with an earlier legacy backup first.

## Pipeline

| Step | File | What it does |
|---|---|---|
| extract | `extract.py` | Copies the 72 needed legacy tables as-is into a fresh `legacy` schema in the target database (picture columns skipped; `tblInventory` only non-zero rows; auto-generated ODR debit memos left out). Tables/columns the database lacks are created empty from `legacy_columns.json`. `legacy.source` records which database it came from. |
| 00 | `sql/00_prepare.sql` | Indexes the legacy copy. |
| 01 | `sql/01_reset_data.sql` | Finds the company, wipes its previous load (see "Several companies"), computes the ID offset and shifts the legacy copy's IDs. Also puts back the column DEFAULTs from `db/init.sql` that a schema created by the app's `ddl-auto=update` lacks, which the bulk INSERTs rely on. |
| 10 | `sql/10_master.sql` | Company, users, employees, catalog, suppliers, customers + outlet warehouses, payment terms, pull-out reasons, bills of materials, banks and bank accounts. |
| 20 | `sql/20_documents.sql` | Inventory and purchasing transactions: headers, lines, loaded quantities, reconstructed documents. |
| 25 | `match_returns.py` | Links each Outlet Delivery Return to the sale it reverses. |
| 30 | `sql/30_sales.sql` | Sales Invoices and Sales Invoice Returns; Item Entries and Item Disbursements as Inventory Adjustments. |
| 35 | `sql/35_accounting.sql` | Accounting start date, customer memos, payments + tenders, deposits, refunds, supplier payments, open items and allocations, bank book, bounced-check history. |
| 40 | `sql/40_ledger.sql` | Inventory movements, balances and CREATED/VOIDED history, generated from the documents; Stock Transfer actions and their ACTION history. |
| 50 | `sql/50_reconcile.sql` | Aligns on-hand with the legacy stock balance. |
| 55 | `db/migrations/003_backfill_master_data_create_audit.sql` | A CREATE audit log per master-data record, so change history starts with "Created" (see `docs/AUDIT.md`). |
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
| `tblSMSKUs`, `tblPricePoints`, `tblItems.SMSKUNo` | `item_skus`. **SM SKU:** goes only to the items whose `SMSKUNo` names it (active or not), never by category and price. An SM SKU that no item names is kept as one item-less SKU (active or not). Every SM SKU gets one brand, **SM** (created by the migration), whatever legacy brand it had. **Price point:** when its `ItemCode` is a real legacy item code (case-insensitive), an active one goes to that item only, whatever its category and price. Otherwise an active one goes to every item of the same category (`ItemDescriptionID` = item `SKUDescriptionID`) whose unit price (`PriceTypeID` 1 = regular, or no type) or focal price (`PriceTypeID` 2) equals its `PricePoint`, so one code can cover many items. Norbiz allows this because codes are unique per item only. Its other columns are kept: brand, price type, `ItemCode` → `store_item_code`, barcode, vendor part, RDS description, active, RDS/Landmark flags, plus encoder/date as `created_by`/`created_at`. A price point that matches no item, or is inactive, is kept as one item-less SKU. When both masters give an item the same code, the price point's row wins. `ImonoSKUNo` and `tblSMSKUs.ItemID` are ignored. |
| `tblSuppliers` | `suppliers` (keep IDs) |
| `tblPullOutReasons`, `tblBillofMaterial`+`RawDetail` | `pull_out_reasons`, `bills_of_materials` |
| `tblInventoryAdjustment` / `tblOutletInventoryAdjustment` | Inventory Adjustment (outlet ones: header ID +100,000; main lines: ID +3,000,000) |
| `tblStockTransfer` | Stock Transfer. `DRId` becomes the DR's `stock_transfer_id`. |
| `tblStockTransferUserActions` | Stock Transfer actions: one `transaction_action_definitions` row per distinct `ActionId` (`ENCODED BY` → code `ENCODED_BY`, name "Encoded By"), ordered as legacy usually took them, no prerequisites (legacy didn't enforce an order), **SUPER_ADMIN only** as a placeholder. A blank `ActionId` becomes the inactive `UNSPECIFIED` action. Each row becomes an `ACTION` history event: `UserId` is an employee ID mapped like other authors, `DateEncoded` keeps its legacy time (some precede the transfer's creation, so they sort before CREATED), the comment becomes the remarks with whitespace collapsed and cut to 255 characters. |
| `tblDeliveryReceipts` | Delivery Receipt |
| `tblOutletReceives` | Outlet Receipt |
| `tblOutletDeliveryReceipts` | Outlet Delivery Receipt |
| `tblOutletDeliveryReturns` | Outlet Delivery Return |
| `tblReturnSlips` | Outlet Pull-out |
| `tblOutletPullOut` | Pull-out Receipt |
| `tblSupplierInvoices` | Direct Purchase Invoice |
| `tblItemReceive` | Purchase Receipt |
| `tblAssembly` + `Detail` + `RawMaterial` | Assembly (raw material lines: ID +1,000) |
| `tblTerms` | `term_days` on customers, suppliers, invoices (0–365) |
| `tblSalesInvoices` + `Details` | Sales Invoice (Direct, main warehouse). Type (FOC, Shopee, …), branch, PO number and agent go into the remarks. |
| `tblSIReturnSlips` + `Details` | Sales Invoice Return (keep IDs). Each line links the invoice line legacy named (`SIDetailID`), at the legacy net ÷ quantity; it moves stock only when legacy posted it (ledger type 14). The slip's "Sales Return" credit memo (`UICustomerReturnId`) isn't migrated as a memo: its amount and applications become the return's credit (`merged` issues). A slip legacy never credited gets no credit (`no-credit`). Invoice lines' `quantity_loaded` = what live returns took back, capped at what was sold (`over-returned`: 1,272 lines legacy returned twice). Returns dated before their invoice keep the legacy date (`before-invoice`). |
| `tblItemEntries` / `tblItemDisbursements` | Inventory Adjustment + / − (headers +300,000 / +400,000, lines +5,000,000 / +6,000,000) |
| `tblBanks` | `banks` (drawee banks; keep IDs) |
| `tblBankAccounts` | `bank_accounts` (keep IDs). The bank comes from the first word of the name ("bdo (Dmark Medical)" → BDO; `sb` → Security Bank, `eastwest` → Eastwest Bank). Opening balance 0 as of the first entry. |
| `tblCustomerPayments` (real ones) + `Details` | Customer Payment + tenders. Mode 1 Cash, 2 Check, 3 Bank Transfer, 4 Others, 5 Credit Card. |
| `tblCustomerCreditMemos` (+ its carrier payment) | Customer CREDIT memo (`CCM`). Legacy posts each memo as a "payment" whose only detail has mode 101; those links become the memo's own applications. The 29 legacy types map to SALES_RETURN / REBATE / PRICE_ADJUSTMENT / OTHER; the type name stays in the line and remarks. |
| `tblCustomerDebitMemos` (hand-made) | Customer DEBIT memo (`CDM`, reason BILLING; ID +1,000,000) |
| `tblCustomerPaymentLinks` | `open_item_allocations` (PayableTypeID 1 Sales Invoice, 2 Debit Memo, 102 Refund) |
| `tblDeposits` + `Details` | Deposit (cash and checks only) + its DEPOSIT bank entry |
| `tblCustomerRefunds` | Customer Refund (cash; legacy keeps no instrument) |
| `tblSupplierPayments` + `Details` + `Links` | Supplier Payment, tenders, allocations to Purchase Invoices; check/transfer disbursements in the bank book |

The legacy ledger (`tblTransactions`, `tblTransactionDetails`) is not migrated as documents. It is only used to classify how each legacy document posted (e.g. a pull-out that went through transit vs straight to on-hand) and to reconcile the result.

Every migrated document keeps its legacy reference and sheet number and carries `origin = MIGRATED`. Norbiz numbering continues after the highest legacy number for each prefix legacy shared (DR, OR, ODR, ODRR, STF, DRR, OPO, ASM), so the next DR after `DR-093121` is `DR-093122`.

## Rules

- **Voided** legacy documents are migrated voided and post no movements. Legacy removed a voided document's ledger rows, and a create + void pair would net to zero.
- **Never-posted documents** are migrated **voided**, with remarks saying why. This covers unposted (draft) Outlet Receipts and supplier invoices that never touched stock and were never received (pre-inventory, 2019–Feb 2023). They had no stock effect in legacy either.
- **Reconstructed documents** (`origin = RECONSTRUCTED`, `RCN-<prefix>-000001`, remarks name the legacy source) fill steps the legacy flow skipped:
  - **Pull-out Receipt:** for each pull-out legacy posted straight into main on-hand.
  - **Purchase Receipt:** for each supplier invoice legacy posted straight into on-hand.
  - **Zero-price Outlet Delivery Receipt:** for return lines no sale could account for.
  - **Inventory Adjustment, over-receiving:** for stock a receipt took beyond what its source still had outstanding (legacy allowed this).
  - **Inventory Adjustment, cutover correction:** one per warehouse, aligning on-hand with the legacy balance at cutover.
- **Receipt allocation:** a receipt line loads its source line only up to what is still outstanding, in date order. The excess becomes the reconstructed adjustment above.
- **Splits:** a Norbiz receipt has one source and a return reverses one sale. A legacy receipt spanning several sources, or a return spanning several sales, is split into one document per source (`<ref>-2`, `<ref>-3`, …).
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
  - Duplicate supplier-invoice and receipt numbers are renamed the same way.
- **Audit:** bulk inserts don't fire `AuditableEntityListener`. Step 55 writes each migrated master-data record a CREATE log in the listener's format, dated at its legacy creation and attributed to its author, so its change history starts with "Created". SKUs, prices and BOM lines get none. One `audit_logs` marker on the company records the load. Migrated rows carry `created_by = legacy-migration` (or the legacy author).
- **Columns nullable only for legacy:** some FKs are nullable just so legacy rows without them fit (e.g. `OutletPullOut.pullOutReason`). The API still requires them.

Every fix is logged in `migration.issues` (kind, entity, legacy ID, detail) and summarised by the report.

## Sales and accounting

Neostrata used the legacy AR/AP subledger; Karutora left it empty, so for Karutora these steps load nothing (and its accounting start date stays as set in the app).

- **Sales Invoices.**
  - Line price = legacy line net ÷ quantity, scaled by the header net over the sum of line nets. This folds in the invoice-level discount 31k invoices carry, so the lines add up to the legacy net amount. Migrated invoices have no header discount.
  - A line posts stock only when legacy did (its ledger has that invoice and item). Non-inventory items never did; nor did 385 invoices legacy left unposted.
  - Empty, zero-value, never-posted invoices are legacy drafts: migrated voided (1,176).
  - Zero-value invoices (FOC, testers, borrowed: 1,084 live) have no receivable and are PAID.
- **Payments and tenders.**
  - Voided payments, tenders and links post nothing.
  - Check status: bounced → BOUNCED; legacy Cleared → CLEARED; in a live deposit → DEPOSITED; else ON_HAND. Cash in a deposit is CLEARED, as `DepositService` does. Transfers, cards and others are CLEARED.
  - **Bounces.** A bounced tender's applications are made and then reversed (BOUNCED) on its bounce date, raising the invoices' `bouncedAmount`. Its credit is voided, and a deposited check gets a CHECK_RETURNED bank entry. Legacy keeps no bounce date, so the check's last deposit date (else check date, else payment date) stands in. Legacy also "bounced" 7 bank transfers; they are kept as BOUNCED, which Norbiz itself only allows for checks.
  - What a tender didn't apply is the customer's advance (unapplied credit), as in Norbiz.
- **Deposits.** Legacy deposited bank transfers and cards too; Norbiz books them straight into the account (`TRANSFER_RECEIPT`, here on the legacy deposit's date and account). So a Norbiz deposit holds only the cash and checks, and the 13,003 legacy deposits that held only transfers aren't migrated as deposits (`merged` issues).
  - A voided legacy deposit keeps the cash and checks it held, with their total, as a deposit voided in Norbiz does. Legacy voids those details along with it; the lines don't link the tender to the voided deposit and post no bank entry. One that held only transfers is migrated voided and empty. Neostrata's 2026-10-23 prod load predates this and is backfilled by `tools/legacy-migration/fixes/2026-10-23_neostrata_voided_deposit_lines.sql` (160 slips, 281 lines).
- **Amounts** are rounded to centavos per charge, cumulatively, so a fully paid legacy invoice closes exactly.
  - An application beyond what is left of the charge or the credit is cut to fit (`trimmed`), oldest kept. Nearly all of these are 0.01 rounding.
  - Two absurd tender amounts (₱879 trillion and ₱6.7 billion, typos) are cut to what they paid (`absurd-amount`).
- **Accounting start date** = the earliest migrated AR/AP document, unless one was already set in the app.
  - Migrated Purchase Invoices dated on/after it are tracked (they open a payable that the migrated supplier payments apply to). Their `net_amount` is the legacy net.
  - Karutora's start date (2026-10-01, set in the app) keeps its migrated invoices untracked.
- **Open items** are written exactly as `OpenItemService` leaves them: amounts = the sums of their active allocations, `pending_amount` = what uncleared checks applied (legacy PDC), `status` OPEN/CLOSED/VOIDED.
- **History:** CREATED/VOIDED for every document, plus a STATUS_CHANGED event per bounced check.

## Reconciliation

The documents reproduce the legacy ledger (`tblTransactionDetails`) almost exactly; the only differences come from the reconstructed documents. Legacy's stored balances (`tblInventory`) disagree with its own ledger for a few thousand outlet item/warehouse pairs. `tblInventory` is what legacy users see as stock, so step 50 aligns on-hand to it.

**Transit** differences can't be corrected by an adjustment. They are reported, not fixed (about 1.4k pairs, 3.3k units, all legacy-internal inconsistencies).

For AR, the report compares every live Sales Invoice's Norbiz balance with legacy's own view: net − the applications of its non-voided, non-bounced tenders (= legacy AmountPaid + PDC). They must agree except where the loader logged a cut or dropped application. Neostrata rehearsal: 238,976 live invoices, 498 explained differences, 0 unexplained; outside those, legacy ₱783,769,909.54 = Norbiz ₱783,769,909.54. Legacy's stored `AmountDue` is stale on ~1,600 invoices and is shown for information only.

Report checks, any failure fails the run (all scoped to the company just loaded):
- Document counts per type equal legacy − skipped + split parts.
- Every return line is linked to a sale.
- On-hand equals the legacy balance.
- Balances equal the sum of movements.
- No line is loaded beyond its quantity.
- Every legacy Stock Transfer action is in the history (minus those on unmigrated transfers).
- Invoice balances match legacy (above).
- Every open item equals its allocations (applied = sum of active allocations, balance = amount − applied).

## Known limitations

- **Payables/receivables:** a company whose legacy AR/AP is empty (Karutora) keeps supplier invoice payment status derived from legacy amount paid, and its migrated invoices stay untracked — carry open balances in as `OPENING_BALANCE` memos dated before its start date if needed. Legacy's auto-generated customer debit memos per ODR are not migrated.
- **Not migrated (no Norbiz equivalent):** the general ledger (`tblJournals`, chart of accounts), VAT and withholding tax, legacy bank statements (`tblDailyBankStatements`; the bank book is rebuilt from the documents instead), purchase orders (Neostrata has 7, from 2005), and the payment audit log (`tblCustomerPaymentsLog`). No legacy bank reconciliation exists, so every bank entry starts uncleared.
- **Customer and supplier details** (address, TIN, credit limit; terms now exist as `termDays` but aren't migrated) and item units, colour and size have no Norbiz fields and aren't migrated.
- **Unmigratable documents:** receipts with no lines, and receipts whose only lines name items not on their source, can't be migrated as documents. They are logged as skipped; any stock they moved is carried by reconstructed adjustments.

## Clashes with Norbiz features (Neostrata)

Places where Neostrata's data doesn't fit what Norbiz implements today. Each has a working default in the loader (the run passes all checks). Decisions taken so far are marked; the rest are still open. Changing a default is a re-run (company-scoped, repeatable). Figures are from the 2026-10-23 rehearsal.

| # | Clash | Loader default today | Decision needed |
|---|---|---|---|
| 1 | **Sales Invoice Return** (27,978 SIR): legacy returns stock to the warehouse *and* raises a "Sales Return" credit memo. | **Decided 2026-10-23: built.** Norbiz now has Sales Invoice Return (`SIR`); legacy returns load into it, the credit memo becoming the return's credit (see Mapping). | — |
| 2 | **Zero-value invoices** (1,084 live: FOC, testers, borrowed, personal use). | **Decided 2026-10-23:** Norbiz now allows zero-value Sales Invoices (no receivable, PAID, voidable). Migrated as such. | — |
| 3 | **Fields Norbiz SIs lack:** sales agent (253k invoices, 182 agents; legacy profit/commission params use it), invoice type (FOC, Shopee, Lazada, Retail Outlets…, 75k), customer branch (4.6k), customer PO no. | Kept in the remarks; agents tagged AGENT. | Add agent and an "invoice type / channel" lookup to Sales Invoice if reports need them. |
| 4 | **Credit memo types:** 29 legacy types (Personal Use 9.6k, Tax Certificate 4.7k, Package Deal, Promo, Discount 3%…) vs 6 Norbiz reasons. | Mapped to SALES_RETURN / REBATE / PRICE_ADJUSTMENT / OTHER; type name kept in line + remarks. | Make memo reasons a company-configurable master if the types matter. |
| 5 | **Checks never cleared:** legacy marked only 20k of 142k checks cleared. | 112,507 deposited checks (₱3.9B) stay DEPOSITED, so their applications count as PDC/pending (₱3.7B on invoices) and they appear as uncleared in Bank Reconciliation. | **Decided 2026-10-23: leave them uncleared for now — ask again on the next Neostrata migration run.** Option: clear deposited checks older than N days at load (a small addition to `35_accounting.sql`), or reconcile them in the app. |
| 6 | **Bounce dates** aren't kept by legacy (8,449 bounced tenders). | Last deposit date (else check/payment date). | Accept, or supply dates. |
| 7 | **Bank transfers / cards went through deposits** in legacy; Norbiz books them straight into the account. | TRANSFER_RECEIPT on the legacy deposit's date and account; the 13,003 transfer-only deposits aren't migrated as deposits (their DEP numbers are absent in Norbiz). | Accept, or allow non-cash lines on Norbiz deposits. |
| 8 | **Returned bank transfers** (7) — Norbiz can bounce checks only. | Kept as BOUNCED with reversed applications. | Accept. |
| 9 | **Items without codes:** 2,186 of 2,462 Neostrata items have no legacy ItemCode (identified by description). Norbiz requires a unique code. | `LEGACY-<legacy id>`. | Supply real codes (bulk update after load) or accept. |
| 10 | **Non-inventory items on invoices** (51 items, ~160 lines; services, machines): Norbiz Direct SI accepts only INVENTORY items. | Migrated lines post no stock. New native invoices for these items will be refused. | Allow non-inventory (service) lines on SIs. |
| 11 | **Item SKUs / price points** (9,414): Karutora's rules (SM SKUs, department-store price points by category + price) match no Neostrata item. | All 9,414 kept as item-less SKUs. | Decide whether Neostrata needs them at all. |
| 12 | **Customer master:** 25,682 customers (25,446 without a code → `C-<id>`); address, TIN, credit limit, branches and the third type END-USER have no Norbiz field. | Code `C-<id>`, type CUSTOMER. | Add fields if needed. |
| 13 | **Shared users:** the same staff appear in both companies. | `Paul`, `flor`, `Chris` linked to their Karutora users; `joan` (another person) → `joan-neostrata`. | **Decided 2026-10-23: confirmed, they are the same people.** |
| 14 | **Accounting start date** for a company with full history = 2000-04-10 (earliest valid legacy date), so its migrated documents behave as native ones (payable, voidable). | Set by the loader when not set. | **Decided 2026-10-23: accepted (2000-04-10).** |
| 15 | **Prod downtime:** Neostrata can only be loaded with Norbiz stopped (Karutora users included), about 5–10 min plus pictures. | — | **Decided 2026-10-23: downtime accepted.** |
