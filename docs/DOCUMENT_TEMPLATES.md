# Document Templates & Printing

Frontend-customizable document printing: a designer (sibling frontend repo) for freely positioning elements on a page and binding them to backend record fields, plus a print action that renders a real record through the saved layout via native browser print (`window.print()` + `@media print`, no PDF library). Follow existing frontend conventions: currency formatting, display name (not username), right-aligned numbers, vertically aligned labels.

**Split of responsibility**: mainly frontend. Backend only persists template layouts (company-scoped) and exposes a bindable-field schema per document type — it stores/returns the layout as **opaque JSON** without validating its contents (exempt from the root `CLAUDE.md` 255-char string rule; this is config, not display text).

- `DocumentTemplate` extends `Auditable` (editable config, not a ledger). Fields: `company` (FK), `documentType` (free-text, e.g. `"INVENTORY_ADJUSTMENT"` — same convention as `InventoryMovement.sourceType`), `name`, `layout` (`TEXT`, opaque JSON), `defaultTemplate` (boolean — **not** `isDefault`, see root `CLAUDE.md`'s `## Gotchas`), `active`.
- **Default templates are generated, never hand-POSTed.** On startup `DataInitializer` calls `DefaultDocumentTemplateProvisioner.ensureDefaultsForAllCompanies()`, which creates `"<Display Name> - Default"` (`defaultTemplate: true`) for every `(company, documentType)` in `DocumentSchemaRegistry` that has **no** default yet. It only fills gaps — an existing default (hand-designed or previously generated) is never overwritten, so customisations survive restarts; delete or un-default a template and the next startup regenerates it. The layout JSON is built by `DefaultDocumentTemplateFactory` from the type's `DefaultDocumentLayout` role hints, following `docs/TRANSACTIONS.md`'s standard layout. Any future company-creation path must call `ensureDefaults(company)` so new companies get templates immediately rather than on next restart.
- Only one `defaultTemplate=true` per `(company, documentType)` — enforced in `DocumentTemplateService` (unsets any prior default on save), not a DB constraint.
- **No stale full-object writes.** Because saving a default un-defaults every other template of that type, a `PUT /{id}` re-sending an old `defaultTemplate: true` silently takes the default back. So the designer saves through `PUT /{id}/layout` (layout only), the metadata form omits `layout` on update (null keeps the stored one; it's required only on create), and the form refetches the record before entering edit mode instead of trusting the list row/record-tab snapshot.
- **Permission model**: one global `MANAGE_DOCUMENT_TEMPLATES` gates designing *and* printing/previewing — deliberately not per-verb CRUD or the target document's own VIEW permission, matching the flat `MANAGE_SYSTEM` precedent.
- **Bindable-field registry**: `DocumentSchemaRegistry` is a hand-maintained static map, `documentType` → `Entry(schema, defaultLayout)`. `schema` is the header fields + repeating groups (e.g. `INVENTORY_ADJUSTMENT` → `referenceNumber`, `sheetNumber`, `companyName`, `warehouseName`, `adjustmentDate`, `reason`, `createdBy`, plus repeating group `lines`: `itemCode`/`itemName`/`quantity`); `defaultLayout` is the `DefaultDocumentLayout` role hints (display name, date/counterparty/remarks fields, tables + column widths) the default template is generated from. Both live in one entry so a type can't get a schema without a default printout; the factory fails fast if a hint names a field missing from the schema (covered by `DefaultDocumentTemplateTest`). New document type = new registry entry here **and** in the frontend's `DOCUMENT_TYPES` array (`DocumentTemplatesPage.tsx`) — no shared source of truth, keep both in sync manually.
- **Company-scoping gotcha**: the print action's default-template lookup uses the *printed record's* company, not the session's active company. A multi-company user needs a separate default template per `(company, documentType)` — switch the active company before creating each one. (Intended behavior, not a bug — the frontend's "no template" error names the missing company for this reason.)

See `docs/TRANSACTIONS.md` for the standard transaction document layout every transaction-type template must follow, and the canonical starting-layout JSON to copy when wiring up a new document type.

## Layout JSON (owned by the frontend, backend never inspects it)
```json
{
  "pageSize": "A4", "orientation": "portrait",
  "elements": [
    { "id": "...", "type": "text", "x": 20, "y": 20, "width": 200, "height": 24, "binding": "referenceNumber", "style": { "fontSize": 14, "bold": true } },
    { "id": "...", "type": "static", "x": 20, "y": 50, "width": 100, "height": 20, "text": "Reference #:" },
    { "id": "...", "type": "table", "x": 20, "y": 200, "width": 400, "height": 300, "binding": "lines",
      "columns": [ { "binding": "itemCode", "label": "Code", "width": 80 }, { "binding": "itemName", "label": "Item", "width": 200 }, { "binding": "quantity", "label": "Qty", "width": 60 } ] }
  ]
}
```
Five element types: `text` (single-field binding), `static` (literal label), `table` (repeating-array binding, one row per item, adjustable per-column `width`, deliberately unstyled — no grid chrome/borders/header row; the only chrome is an optional separator under each row, drawn when its `style.borderColor` is set, with `borderWidth` as thickness), `line` (horizontal/vertical rule), `shape` (bordered/filled rectangle for sectioning). `TemplateElementStyle`: `fontSize`, `bold`, `align`, plus `borderWidth`/`borderColor`/`fillColor` for line/shape (`borderWidth`/`borderColor` also mean the row separator on a table).

## Designer (`react-rnd`)
Click a palette field/shape to drop it on the canvas, then drag/resize via `react-rnd`. One shared `TemplateRenderer` powers both the designer (edit mode, draggable/resizable) and the print view (read-only) so positioning/binding logic never drifts between them. Snap-to-grid is edit-only UI state, never persisted into the layout.

**Required Vite config**: keep `define: { 'process.env': {} }` in `vite.config.ts`. `react-rnd`'s `react-draggable` dependency reads `process.env.DRAGGABLE_DEBUG`, which Vite doesn't polyfill — removing the shim blanks the whole app (`ReferenceError: process is not defined`, no error boundary) the instant a draggable element mounts.

## Print flow
Transaction forms use `<PrintButton>`, which first calls `GET /document-templates/printable` (the active templates for `(companyId, documentType)`, default first then by name). One template prints straight away; several open a menu to pick from; none shows the page's "no template" error. `useDocumentPrint` then fetches the picked template (or the default when no id is passed), portals `<TemplateRenderer mode="print">` into an off-screen `#document-print-root`, then calls `window.print()`. Global `@media print` CSS hides all app chrome (`visibility: hidden` on `body *`, restored for `#document-print-root` and descendants) — required infrastructure, not page-specific styling.
