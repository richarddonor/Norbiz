# Dashboard widgets

The dashboard is a per-user board of **widgets**: small live reports (KPI tiles, charts, short lists) that each user pins, orders and resizes for themselves.

## Model

- **Catalog**: the `DashboardWidget` enum. Each constant has a `slug` (URL segment and the stable key the frontend stores), a display name, a category and a description.
- **Permission per widget**: `VIEW_DASHBOARD_<CONSTANT>` (description `Dashboard - <Name>`). It's seeded by `DataInitializer` from the enum and granted to SYSTEM_ADMIN/SUPER_ADMIN, the same way detailed-report permissions are. It's independent of the underlying transaction's `VIEW_` permission, so an admin can show a manager a summary without opening the transaction module.
- **User layout**: which widgets a user pinned, in what order, and how wide, is stored as their own `UserPreference` under key `dashboard.layout`. Its value is opaque frontend JSON: `{version: 1, widgets: [{key, wide, options?}]}`. There's no backend table for it. With no saved layout, the frontend shows every permitted widget. A widget whose permission was later revoked stays in the saved layout but is neither listed nor drawn.

## Endpoints (`DashboardController`)

`GET /dashboard/widgets` (any authenticated user) returns the catalog filtered to the caller's `VIEW_DASHBOARD_*` authorities. It's a small fixed list, so it isn't paginated. Each widget is `GET /dashboard/widgets/<slug>` behind `VIEW_DASHBOARD_<CONSTANT>`:

| Slug | Category | What it computes |
|---|---|---|
| `pending-outlet-receipts` | Inventory | Backlog: outlet Delivery Receipts not voided, not `loaded`, with outstanding `quantity − quantityLoaded`. Value at the DR line's selling price. Short aging. Progress = share already received. |
| `stock-in-transit` | Inventory | `InventoryBalance` summed per warehouse: transit vs on-hand, totals by kind (MAIN/OUTLET/OTHER), top 8 warehouses by \|transit\| + Others. Main-warehouse transit nets incoming purchases/pull-outs against negative holds from open Stock Transfers. Not dated, so no `asOf`. |
| `outlet-stock-health` | Inventory | For every outlet × item pair the outlet sold in the last `days` (7–90), days of cover = on hand ÷ (units sold ÷ days). Counts stock-outs (on hand ≤ 0) and low cover (< 7 days); grid of the 10 busiest outlets × 8 best-selling items; 8 worst pairs. |
| `inventory-adjustment-trend` | Inventory | Non-voided adjustment lines per day over `days` (7–365): units added (+ lines) and removed (− lines), top 5 warehouses by units moved, top 5 reasons (grouped trimmed + case-insensitive). Quantities only. |
| `pending-purchase-orders` | Purchases | Backlog: POs not voided, not invoiced (`loaded`), with outstanding quantity. Value = outstanding × line cost. Long aging. Breakdown = destination warehouse. |
| `unpaid-purchase-invoices` | Purchases | Backlog: invoices not voided and not `PAID`, aged from the invoice date. Amount = net payable (discounted lines × (1 − header discount %) + fees), same as the invoice response. No quantity. Breakdown = payment status. |
| `outlet-sales` | Sales | ODR sales minus ODR returns over `days` (7–365): zero-filled daily series, totals, previous equal period's net, top 5 outlets and agents. |
| `agent-leaderboard` | Sales | Top 10 agents by net outlet sales over `days` (7–365): gross, returns, document count, daily net series, previous period's net and rank. |
| `pull-outs-awaiting-receipt` | Sales | Backlog: Outlet Pull-outs not voided, not fully received by Pull-out Receipt. Value at the pull-out line's selling price. Short aging. Breakdown = pull-out reason (hidden when every row has none, as on migrated data). |
| `transaction-activity` | Operations | `TransactionEvent` CREATED/VOIDED counts per type per calendar day over `days` (7–90), bucketed in the caller's IANA `tz` (400 on an unknown zone). Migrated documents count on their legacy `created_at`. |
| `receivables-aging` | Accounting | OPEN RECEIVABLE open items with a balance. Charges aged by days past due (`dueDate`, else document date) into the AR aging report's buckets (Current, 1-30 … 120+). Totals: outstanding, overdue, due within 7 days, `pendingAmount` (paid by uncleared checks), unapplied credits (advances, credit memos). Billed (Sales Invoices + customer debit memos) vs collected (tenders of non-voided payments, minus BOUNCED/REPLACED) over `days` (7–365). Top 8 customers + Others; the 8 most overdue charges. |
| `payables-due` | Accounting | Same shape (`SubledgerSummaryResponse`) for PAYABLE items, bucketed by when they fall due: overdue 31+ / 1-30, due in 0-7 / 8-30 / 31+ days. Billed = Purchase Invoices + supplier credit memos; paid = supplier tenders minus CANCELLED. Not gated by `VIEW_COST_PRICE` (open-item balances, like the AP aging report). Untracked (migrated/pre-start-date) Purchase Invoices have no open item, so they only show in `unpaid-purchase-invoices`. |
| `cash-and-checks` | Accounting | Per bank account: book balance as of `asOf` (opening + non-voided entries, as in reconciliation), uncleared money in/out, last non-voided reconciliation date. Received tenders: CASH/CHECK `ON_HAND` (post-dated = check date after `asOf`), `DEPOSITED`, `BOUNCED` in the last 30 days. Our ISSUED supplier checks. Lists the 8 next checks to deposit, 8 oldest outstanding, 8 latest bounces. |

**Backlog widgets** (the four marked "Backlog") share `BacklogResponse` and `DashboardService.backlog(...)`: totals, optional progress %, aging buckets (short: 0-3/4-7/8-14/15-30/31+ days; long: 0-30/31-60/61-90/91-180/181+), top 8 counterparties + one `Others` slice (`id: null`), an optional breakdown, and the 8 oldest documents. Fields a widget doesn't have are null. On the frontend they're all one `BacklogWidget` configured in `registry.tsx`.

**Cost gating:** the PO and PI widgets are cost-based, so their amounts are null without `VIEW_COST_PRICE` (the controller passes the flag; slices then rank by quantity or document count).

Every widget endpoint:
- is scoped to **one** company: the `companyId` param, else the `X-Company-Id` header (required). `assertCompanyAccess` runs first.
- takes `asOf` (`yyyy-MM-dd`), the caller's local date. Aging and periods are measured against it, since business dates are UTC-midnight instants and the server's UTC "today" lags the Philippines by 8 hours. It defaults to today in UTC.
- is served from a short-lived Redis **snapshot** (`app.cache.ttl.dashboard`, default 2 min) taken after the company-access check. Snapshots are not invalidated by writes, so a widget can lag a posting by up to the TTL. Everything that shapes the response (`asOf`, `days`, `timeZone`, cost-price visibility) is in the cache key, see `docs/CACHING.md`. All SQL lives in `repositories/DashboardQueries` (JPQL tuple queries, one native query for the time-zone bucketing).

## Adding a widget

1. Add a `DashboardWidget` constant (this creates its permission on restart).
2. Add its query to `DashboardQueries`, a `DashboardService` method that calls `assertCompanyAccess` first (via `begin(...)`), and an endpoint in `DashboardController` with its own `@PreAuthorize`. A "documents still waiting" widget should return `BacklogResponse` through `backlog(...)`.
3. Frontend: add a widget component in `src/components/dashboard/` (or a `backlog({...})` config) and register it in `registry.tsx` under the same slug. The catalog only lists widgets the frontend has registered.
