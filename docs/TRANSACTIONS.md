# TRANSACTIONS.md

Specification for **Transactional Data** in Norbiz — day-to-day business records that make use of master data (Sales Orders, Invoices, Purchases, Inventory Movements, Accounting records, and so on). See the root `CLAUDE.md` for general project architecture, multi-tenancy, and the Document Templates & Printing system this spec builds on.

## General rules
- Transactions are non-master data records. This includes inventory movements, financial transactions, and other executions that make use of master data.
- Always auto-generate a transaction reference number value for each new transaction.
- Most transactions will have a sheet number value. Sheet number is based on the control number in the physical document of that transaction.
- Always have a Notes column for comments or remarks.
- Always add a default document template for printing (see `## Standard transaction document layout` below).

### Reference number generation
`TransactionReferenceService.next(companyId, transactionType, prefix)` is the shared mechanism every transaction type should call to satisfy the auto-generated reference number rule — do not hand-roll numbering per transaction type. It's backed by `TransactionSequence` (table `transaction_sequences`, one row per `(company_id, transaction_type)`, locked with `PESSIMISTIC_WRITE` and incremented in its own `REQUIRES_NEW` transaction so the counter still advances if the caller's transaction rolls back — numbers may have gaps but never repeat). Reference numbers are formatted `{PREFIX}-{6-digit zero-padded number}` (e.g. `IA-000001` for Inventory Adjustment) and are unique per `(company_id, reference_number)` on the owning transaction table. `sheetNumber` is plain user input (nullable, not generated) — only capture it on request DTOs, never derive it.

## Transaction types

### Inventory Adjustment
- This is an inventory movement transaction to increase or decrease item inventory.
- Reference prefix: `IA`.
- Counterparty field: `Warehouse` — Inventory Adjustment has no customer/supplier.
- Posts to `InventoryMovement` (append-only ledger) and `InventoryBalance` (running quantity/transitQuantity cache) — see root `CLAUDE.md`'s `## Inventory Management` section for the full ledger/balance model.
- Document type for printing: `INVENTORY_ADJUSTMENT`. Notes field is `reason`, labeled "Remarks" in the `DocumentSchemaRegistry` entry to match the general Notes/Remarks convention above.
- Carries `voided`/`voidedAt`/`voidedBy` and `loaded` (header) / `quantityLoaded` (line) — see `## Voiding` / `## Transaction Loading` below. `loaded`/`quantityLoaded` are inert here: nothing currently loads *from* an Inventory Adjustment.

### Purchase Order — implemented
- This transaction lets users buy goods from different Suppliers. It is a requisition for items into inventory (transit) — it carries no payable/payment status; Purchase Invoice is the payable transaction (see `## Purchase Invoice` below).
- Reference prefix: `PO`.
- Counterparty field: `Supplier`. Also carries a destination `Warehouse` — creating a PO posts a Transit Quantity increase there (`transitQuantityDelta = +quantity` per line), reversed on void; see root `CLAUDE.md`'s `## Inventory Management`.
- User specifies which Supplier they are buying the items from.
- Each line carries `quantity` (must be `> 0`) and `costPrice` — sensitive, gated by the existing `VIEW_COST_PRICE` permission (not a new one); preloaded from the item's current cost price when the request omits it, but overridable per line (a PO can legitimately negotiate a different price than the item's master cost price).
- Carries `voided`/`voidedAt`/`voidedBy` and `loaded`/`quantityLoaded`, same as Inventory Adjustment. `loaded`/`quantityLoaded` are **no longer inert** and are now shared by two possible consumers, mutually exclusive with each other (see `## Purchase Receive` below for why): creating a Purchase Invoice against a PO (see `## Purchase Invoice` below) loads it in full in one shot, blocking further invoicing/receiving and voiding until the invoice is voided; alternatively, one or more Purchase Receives can load it partially or in full directly, without ever being invoiced (blocking invoicing once any receiving has happened, and blocking voiding once `loaded` or any line `quantityLoaded > 0`).
- Document type for printing: `PURCHASE_ORDER`. Notes field is `remarks` (named directly, not `reason`, since this was a fresh entity).

### Sales Order (future)
- Sells goods to Customers, or consigns them to Outlets (see root `CLAUDE.md`'s `## Customers` section).
- Counterparty field: Customer/Outlet.
- Not yet implemented.

## Standard transaction document layout
**Every transaction-type document template must follow this base layout** — not just a suggestion, this is the required starting point for `InventoryAdjustment` today and for `PurchaseOrder`/`SalesOrder`/any future transaction type, so printed documents stay visually consistent across the whole system. Top to bottom:
1. Company name (letterhead) and a document-type title.
2. Header row: **transaction reference number** and **sheet number** side by side.
3. **Transaction date**.
4. **Customer/Supplier** — only for transaction types that actually have one (future Purchase Order → Supplier, Sales Order → Customer/Outlet). `InventoryAdjustment` has no customer/supplier; its equivalent "counterparty" field is `Warehouse`, shown in that position instead. Whatever a transaction type's counterparty concept is (Supplier, Customer/Outlet, Warehouse, ...), it goes in this slot.
5. **Remarks** — the transaction's notes/comments field (see `## General rules` above; on `InventoryAdjustment` this binds to `reason`, labeled "Remarks" in the schema registry to match — every transaction type's notes field should likewise be labeled "Remarks" in its schema registry entry regardless of its underlying column name).
6. **Line items** table, positioned at the **bottom** of the page — below every header field above, occupying the remaining page height. Column widths are per-template adjustable (`TemplateTableColumn.width`), not fixed.

The `INVENTORY_ADJUSTMENT` default templates (one per company, named `Inventory Adjustment - Default`) are built to this layout and are the canonical reference implementation.

**Checklist for wiring up a new transaction type's print template** (e.g. when `PurchaseOrder` is built):
1. Add a `DocumentSchemaRegistry` entry on the backend for the new `documentType`, with the notes field labeled "Remarks" and the counterparty field (Supplier/Customer/Outlet) included as a header field.
2. Mirror it in the frontend's `DOCUMENT_TYPES` array (`DocumentTemplatesPage.tsx`).
3. Build a default template per company using the coordinate layout below as the starting point — swap the field `binding`s for the new type's schema paths, keep the structure (letterhead → title → separator → ref#/sheet#/date row → counterparty row → remarks row → separator → line items title → column headers → separator → table → footer) and the same x/y positions where the field role is equivalent. **There is no seed script for this** — `db/init.sql`/`DataInitializer` never create `DocumentTemplate` rows (see root `CLAUDE.md`). Do it by hand: `POST /document-templates` once per existing company with this layout and `defaultTemplate: true`.
4. Set `defaultTemplate: true` per company and verify the print action resolves it (see root `CLAUDE.md`'s company-scoping gotcha under Document Templates & Printing — one default per `(company, documentType)`).

### Canonical starting layout (A4 portrait, 794×1123px) — copy and re-bind for a new document type
```json
{
  "pageSize": "A4", "orientation": "portrait",
  "elements": [
    { "id": "company-name", "type": "text", "x": 20, "y": 20, "width": 400, "height": 28, "binding": "companyName", "style": { "fontSize": 18, "bold": true } },
    { "id": "doc-title", "type": "static", "x": 20, "y": 52, "width": 400, "height": 20, "text": "<DOCUMENT TYPE TITLE>", "style": { "fontSize": 12, "bold": true } },
    { "id": "sep-1", "type": "line", "orientation": "horizontal", "x": 20, "y": 85, "width": 754, "height": 1, "style": { "borderWidth": 1, "borderColor": "#000000" } },

    { "id": "ref-label", "type": "static", "x": 20, "y": 100, "width": 90, "height": 18, "text": "Reference #:", "style": { "bold": true } },
    { "id": "ref-value", "type": "text", "x": 115, "y": 100, "width": 150, "height": 18, "binding": "referenceNumber" },
    { "id": "sheet-label", "type": "static", "x": 290, "y": 100, "width": 70, "height": 18, "text": "Sheet #:", "style": { "bold": true } },
    { "id": "sheet-value", "type": "text", "x": 365, "y": 100, "width": 150, "height": 18, "binding": "sheetNumber" },
    { "id": "date-label", "type": "static", "x": 540, "y": 100, "width": 50, "height": 18, "text": "Date:", "style": { "bold": true } },
    { "id": "date-value", "type": "text", "x": 595, "y": 100, "width": 150, "height": 18, "binding": "<transactionDate>" },

    { "id": "counterparty-label", "type": "static", "x": 20, "y": 128, "width": 90, "height": 18, "text": "<Warehouse:|Supplier:|Customer:>", "style": { "bold": true } },
    { "id": "counterparty-value", "type": "text", "x": 115, "y": 128, "width": 400, "height": 18, "binding": "<warehouseName|supplierName|customerName>" },

    { "id": "remarks-label", "type": "static", "x": 20, "y": 156, "width": 90, "height": 18, "text": "Remarks:", "style": { "bold": true } },
    { "id": "remarks-value", "type": "text", "x": 115, "y": 156, "width": 659, "height": 18, "binding": "<notesField>" },

    { "id": "sep-2", "type": "line", "orientation": "horizontal", "x": 20, "y": 190, "width": 754, "height": 1, "style": { "borderWidth": 1, "borderColor": "#000000" } },
    { "id": "lines-title", "type": "static", "x": 20, "y": 200, "width": 200, "height": 18, "text": "Line Items", "style": { "bold": true } },

    { "id": "lines-header-col1", "type": "static", "x": 20, "y": 224, "width": 120, "height": 18, "text": "<Col 1>", "style": { "bold": true, "fontSize": 11 } },
    { "id": "lines-header-col2", "type": "static", "x": 150, "y": 224, "width": 400, "height": 18, "text": "<Col 2>", "style": { "bold": true, "fontSize": 11 } },
    { "id": "lines-header-col3", "type": "static", "x": 560, "y": 224, "width": 100, "height": 18, "text": "<Col 3>", "style": { "bold": true, "fontSize": 11, "align": "right" } },
    { "id": "sep-3", "type": "line", "orientation": "horizontal", "x": 20, "y": 246, "width": 754, "height": 1, "style": { "borderWidth": 1, "borderColor": "#000000" } },

    { "id": "lines-table", "type": "table", "x": 20, "y": 252, "width": 754, "height": 780, "binding": "<repeatingGroupPath>",
      "columns": [
        { "binding": "<col1Path>", "label": "<Col 1>", "width": 130 },
        { "binding": "<col2Path>", "label": "<Col 2>", "width": 410 },
        { "binding": "<col3Path>", "label": "<Col 3>", "width": 100 }
      ],
      "style": { "fontSize": 11 } },

    { "id": "sep-4", "type": "line", "orientation": "horizontal", "x": 20, "y": 1050, "width": 754, "height": 1, "style": { "borderWidth": 1, "borderColor": "#000000" } },
    { "id": "posted-label", "type": "static", "x": 20, "y": 1060, "width": 80, "height": 18, "text": "Posted by:", "style": { "fontSize": 10 } },
    { "id": "posted-value", "type": "text", "x": 105, "y": 1060, "width": 300, "height": 18, "binding": "createdBy", "style": { "fontSize": 10 } }
  ]
}
```
## Voiding
- All transactions are immutable by default meaning once it is saved it can no longer be edited. The only way to cancel the transaction is by voiding it
- This status is represented by isVoided boolean field ("Void" in frontend for simplicity)
- If a transaction can be loaded to another transaction (see next section of this .md), disallow voiding if transaction is already loaded(full or partially) 
- User can only void if he/she has a voiding permission VOID_<transaction>  

**Implementation notes** (Inventory Adjustment, Purchase Order, Purchase Invoice, Purchase Receive):
- Entity field is named `voided` (not `isVoided`) — Lombok/Jackson naming gotcha, see root `CLAUDE.md`'s "Naming gotcha (Lombok + Jackson)" under Document Templates & Printing. Lombok still generates `isVoided()`; frontend label stays "Void"/"Voided".
- Voiding also stamps `voidedAt`/`voidedBy` for accountability (not part of the original spec text, but the natural minimum to know who/when).
- **Voiding reverses the ledger effects the transaction posted**, not just the flag — otherwise "voided" would be cosmetic while the stock/transit change stays live. Inventory Adjustment posts a compensating `InventoryMovement` (`quantityDelta` negated); Purchase Order and Direct-mode Purchase Invoice post a compensating transit movement (`transitQuantityDelta` negated); Purchase Receive posts a compensating movement on **both** sides at once (`quantityDelta` negated, `transitQuantityDelta` un-negated — i.e. giving the received amount back to transit). Reversals use a distinguishable `sourceType` (`INVENTORY_ADJUSTMENT_VOID` / `PURCHASE_ORDER_VOID` / `PURCHASE_INVOICE_VOID` / `PURCHASE_RECEIVE_VOID`) with the same `sourceId`/`referenceNumber` as the original, for ledger traceability. This is safe for Inventory Adjustment/Purchase Order/Purchase Invoice because void is only allowed when nothing has been loaded yet, so the full original quantity is always outstanding to reverse. A PO-based Purchase Invoice has no transit movement of its own to reverse — voiding it instead unloads the originating Purchase Order (see `## Purchase Invoice`). Purchase Receive is the one exception to "void requires nothing loaded": nothing currently loads *from* a Receive, so it can always be voided (while not already voided) — voiding un-loads whichever source (PO or Direct invoice) it fed by the voided line amounts, reopening it for further receiving/invoicing (see `## Purchase Receive`).
- Endpoint: `POST /{resource}/{id}/void`, gated by `VOID_<TRANSACTION>`, returns the updated resource.

## Transaction Loading
- Some transactions can be loaded to another transaction. 
- For example, a Purchase Order can be loaded to Purchase Receive to process what purchased items are already received.
- They are can be loaded (processed) partially or it full.
- This is represented in the source transaction as isLoaded boolean field. A isLoaded true field means it fully processed while false means it is partially or not processed at all
- For partial loading, this happens usually for itemized transactions, have a quantityLoaded to track how many were already processed. When all items are loaded then set the isLoaded field to true

**Implementation notes**: fields are named `loaded`/`quantityLoaded` (not `isLoaded`), same naming gotcha as above. Inventory Adjustment carries these fields but they remain **inert** — nothing loads from it. Purchase Order's fields have **two possible consumers**, mutually exclusive with each other on a given PO: a PO-based Purchase Invoice (loads it in full, 1:1, in one shot) or one-or-more Purchase Receives (load it partially or in full, directly, without ever being invoiced). Purchase Invoice itself also carries `loaded`/`quantityLoaded`: inert for PO-based invoices (nothing of their own to load — the goods movement belongs to the originating PO), and now genuinely consumed for Direct-mode invoices by Purchase Receive (see `## Purchase Receive` below). Purchase Receive itself also carries `loaded`/`quantityLoaded`, which stay **inert** — nothing currently loads from a Receive.


## Purchase Invoice — implemented
- This transaction posts a payable to a supplier. 
- The payable can be based on a Purchase Order. Purchase Order is `loaded` per line item to the Purchase Invoice. Purchase Order is loaded in full thus maintaining a 1:1 relationship with Purchase Invoice
- The total payable amount from Purchase Order is the net of quantity times cost price
- Discounting can be done per item and for the transaction as a whole. Allow discounting by percentage.
- There will be additional payable entries for additional fees
- The net payable will be settled in a future module Supplier Payments
- Purchase Invoice Direct. There can be a transaction flow that merges Purchase Order and Purchase Invoice into one. This Purchase Invoice will post the same inventory movement behavior (posting to intransit quantity) and can be received/loadable to the Purchase Receive module

**Implementation notes**:
- Reference prefix: `PINV`.
- Counterparty field: `Supplier`, same as Purchase Order.
- Two creation modes, selected by whether the request supplies a `purchaseOrderId`:
  - **PO-based**: loads the given Purchase Order in full (1:1) — lines are copied verbatim (`quantity`, `costPrice`) from the PO's lines; the request's `lines` are only used to supply an optional per-item `discountPercentage` override, matched by `itemId`. `warehouseId`/`supplierId` on the request must match the PO's own warehouse/supplier. Rejected if the PO is voided or already `loaded` (a PO can only be invoiced once). Posts **no** inventory movement — the PO already posted Transit Quantity at its own creation. Sets `PurchaseOrder.loaded = true` and each `PurchaseOrderLine.quantityLoaded = quantity` (full).
  - **Direct** (`purchaseOrderId` omitted): behaves like a merged PO+Invoice — `lines` is required, each line posts a Transit Quantity increase exactly like `PurchaseOrder.create` does (`sourceType = "PURCHASE_INVOICE"`). Its own `loaded`/`quantityLoaded` fields are consumed by Purchase Receive (see `## Purchase Receive` below), same as `PurchaseOrder.loaded`/`quantityLoaded` can be.
- `discountPercentage` (0–100) exists at both the header (`PurchaseInvoice`) and line (`PurchaseInvoiceLine`) level, per the discounting rule above. Additional fees are separate `PurchaseInvoiceFee` rows (`description` + `amount`), added to the payable after discounts.
- `itemsSubtotal`/`discountAmount`/`netPayable` are **computed on the fly** in the response, not persisted (mirrors how Purchase Order doesn't store a total either — nothing here changes after creation). They're derived from cost price, so they're gated by `VIEW_COST_PRICE` the same way `costPrice` itself is; `feesTotal` is always visible since it's independent of cost price.
- `paymentStatus`: 3-state enum `UNPAID` / `PARTIALLY_PAID` / `PAID` — Purchase Invoice is the payable transaction (Purchase Order is just a requisition/inventory-transit posting and carries no payment status). Not settable via the request, always starts `UNPAID`, changed only by the future Supplier Payments module.
- Carries `voided`/`voidedAt`/`voidedBy` and `loaded`/`quantityLoaded`, same shape as Purchase Order. Void is blocked if `loaded` (or any line `quantityLoaded > 0`) — i.e. once a Direct invoice has itself been loaded by a future Purchase Receive. Voiding a **Direct** invoice reverses its own transit movement (`sourceType = "PURCHASE_INVOICE_VOID"`). Voiding a **PO-based** invoice instead unloads the originating PO (`loaded = false`, `quantityLoaded` reset to 0 on each line), making it voidable/invoiceable again.
- Document type for printing: `PURCHASE_INVOICE`. Notes field is `remarks`, labeled "Remarks" per the general convention. Its `DocumentSchemaRegistry` entry has two repeating groups, not just one — `lines` (`itemCode`/`itemName`/`quantity`/`costPrice`, same as Purchase Order) plus `fees` (`description`/`amount`) — the first document type with more than one repeating group, since it's the first with a second itemized collection alongside line items.

## Purchase Receive — implemented
- This transaction accepts intransit items into actual inventory. It carries no payable/cost data of its own — Purchase Invoice is the payable transaction; Purchase Receive is purely a goods-movement transaction, deducting from Transit Quantity and adding to main Quantity for whichever items are physically received.
- Reference prefix: `PR`.
- Counterparty field: `Supplier` — copied from (and validated against) the source. Also carries a destination `Warehouse`, same rule.
- Posted against the outstanding intransit quantity of exactly one source, selected by which ID the request supplies:
  - **`purchaseOrderId`**: receives against a Purchase Order directly. Rejected if the PO is voided or already `loaded` (fully invoiced or fully received elsewhere).
  - **`purchaseInvoiceId`**: receives against a **Direct-mode** Purchase Invoice only — rejected with a clear message if the invoice is PO-based ("nothing of their own to receive — receive against the originating purchase order instead"), since a PO-based invoice's goods movement belongs to its originating PO. Rejected if the invoice is voided or already `loaded`.
  - Exactly one of the two IDs must be supplied; `warehouseId`/`supplierId` on the request must match the source's own warehouse/supplier.
- Each request line supplies `itemId` + `quantity` (the amount being received *now*, not the total ordered/invoiced). Matched against the source's own lines by `itemId`; rejected if the item isn't on the source, or if `quantity` exceeds that line's outstanding (`quantity - quantityLoaded`) amount. **Supports partial receiving**: multiple Purchase Receives can be posted against the same source over time as goods arrive in batches, each one further depleting the outstanding amount, until the source's every line is fully received and it flips to `loaded = true`.
- Posts an `InventoryMovement` per line with **both** deltas non-zero at once — `quantityDelta = +quantity` (main Quantity increases), `transitQuantityDelta = -quantity` (Transit Quantity decreases) — `sourceType = "PURCHASE_RECEIVE"`. This is the first transaction type to post a non-zero `quantityDelta` alongside a non-zero `transitQuantityDelta` in the same movement row.
- Also increments the matched source line's own `quantityLoaded` by the received amount, and recomputes the source header's `loaded` flag (`true` only once every one of the source's lines is fully received) — same "shared field" mechanics as Purchase Invoice does against a PO, just from the other direction.
- **Mutual exclusion with Purchase Invoice on a Purchase Order** (see `## Purchase Order`'s implementation notes and `## Transaction Loading` above): since `PurchaseOrder.loaded`/`quantityLoaded` is shared between "invoiced" and "received" consumers, `PurchaseInvoiceService.loadAndValidatePurchaseOrder` rejects PO-based invoicing once **any** receiving has happened against that PO (even partial) — otherwise the invoice's unconditional full-quantity copy would silently clobber the receive's `quantityLoaded` tracking. In short: a given PO is consumed by an invoice *or* by receive(s), never both.
- Carries `voided`/`voidedAt`/`voidedBy` and `loaded`/`quantityLoaded`, same shape as every other transaction type here — but **inert**: nothing currently loads from a Purchase Receive. Voiding has no "already loaded" guard as a result (see `## Voiding` above) — it reverses the goods movement (`quantityDelta`/`transitQuantityDelta` both negated relative to the original) with `sourceType = "PURCHASE_RECEIVE_VOID"`, and decrements the matched source line's `quantityLoaded` by the voided amount, recomputing the source's `loaded` flag back down as needed.
- Document type for printing: `PURCHASE_RECEIVE`. Notes field is `remarks`, labeled "Remarks" per the general convention. Its `DocumentSchemaRegistry` entry has one repeating group, `lines` (`itemCode`/`itemName`/`quantity` — no `costPrice`, since Purchase Receive carries no cost data).
- **No default print template exists yet** — per the checklist under `## Standard transaction document layout`, someone still needs to `POST /document-templates` once per company with `defaultTemplate: true` for `documentType: "PURCHASE_RECEIVE"`, and mirror the type in the frontend's `DOCUMENT_TYPES` array.