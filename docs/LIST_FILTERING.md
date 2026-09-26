# List Filtering & Search

The frontend (sibling repo `Norbiz-Web`, see its `CLAUDE.md`) drives these requirements — check there when a list-page task's backend shape is unclear.

- Column filters must be backend-side, case-insensitive partial-match ("contains") queries, one filter per column, combined with AND.
- Repositories backing a filterable list should extend `JpaSpecificationExecutor<T>` in addition to `JpaRepository<T, Long>`. Compose filters via `com.chardizard.Norbiz.util.SpecificationUtils` (`containsIgnoreCase`, `anyContainsIgnoreCase`, `allOf`) rather than hand-rolling a derived-name/`@Query` method per filter combination — this is the established pattern going forward (see `ItemRepository`/`ItemService` for the initial wiring).
- Date-type columns must support range filtering (`from`/`to` query params resolved to actual dates). The frontend resolves canned ranges (Today, Current Week, Current Month, Last 30 Days, Last 3 Months, Current Year) to concrete dates client-side — the backend only ever receives `from`/`to`, never a range label.
- The frontend's "Global Filter" search box is frontend-only (searches already-fetched page data, including hidden columns) — do **not** build a backend endpoint or query param for it.
- Spreadsheet export (list/report pages) — scope not yet decided: unclear whether export operates only on the currently-fetched page or needs a separate "fetch all matching rows" backend capability. Confirm before implementing.
