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

### Purchase Order
- This transaction lets users buy goods from different Suppliers.
- Counterparty field: `Supplier`.
- User will specify which Supplier they are buying the items from
- In the line item user will provide the quantity and for how much (cost price). Cost price will be preloaded based from `Item` cost price
- There will be field Payment Status for tracking if the Purchase Order is paid or not. This will be linked to a future Supplier Invoice Transaction
- This transaction can be loaded to the Purchase Receive to record how many items are received. 

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
3. Build a default template per company using the coordinate layout below as the starting point — swap the field `binding`s for the new type's schema paths, keep the structure (letterhead → title → separator → ref#/sheet#/date row → counterparty row → remarks row → separator → line items title → column headers → separator → table → footer) and the same x/y positions where the field role is equivalent.
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

## Transaction Loading
- Some transactions can be loaded to another transaction. 
- For example, a Purchase Order can be loaded to Purchase Receive to process what purchased items are already received.
- They are can be loaded (processed) partially or it full.
- This is represented in the source transaction as isLoaded boolean field. A isLoaded true field means it fully processed while false means it is partially or not processed at all
- For partial loading, this happens usually for itemized transactions, have a quantityLoaded to track how many were already processed. When all items are loaded then set the isLoaded field to true