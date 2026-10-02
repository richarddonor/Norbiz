# Entity Model & Key Service Patterns

## Entity relationships

```
Company ──< User (many-to-many via user_companies)
Company ──< Brand
Company ──< Employee (optional link to User)
Company ──< Warehouse
Company ──< Supplier
Company ──< Customer (type: CUSTOMER | OUTLET)
Company ──< Item ──< ItemSku
                └──< ItemPrice (one row per PriceType enum value)
                └──> ItemCategory (unique name per company)
Company ──< InventoryAdjustment ──< InventoryAdjustmentLine ──> Item
                                └──> Warehouse
Company ──< PurchaseOrder ──< PurchaseOrderLine ──> Item
                          └──> Warehouse (destination — posts Transit Quantity)
                          └──> Supplier (counterparty)
Company ──< PurchaseInvoice ──< PurchaseInvoiceLine ──> Item
                             │                       └──> PurchaseOrderLine (optional — set when PO-based)
                             ├──< PurchaseInvoiceFee
                             ├──> Warehouse
                             ├──> Supplier (counterparty)
                             └──> PurchaseOrder (optional — invoiced-against PO, loaded in full 1:1)
Company ──< PurchaseReceive ──< PurchaseReceiveLine ──> Item
                             │                       ├──> PurchaseOrderLine (optional — set when source is a PO)
                             │                       └──> PurchaseInvoiceLine (optional — set when source is a Direct invoice)
                             ├──> Warehouse
                             ├──> Supplier (counterparty)
                             ├──> PurchaseOrder (optional — one of two possible sources)
                             └──> PurchaseInvoice (optional — the other possible source, Direct-mode only)
Item + Warehouse ──< InventoryMovement (append-only ledger; posted by InventoryAdjustment, PurchaseOrder, Direct-mode PurchaseInvoice, PurchaseReceive, and future transactions)
Item + Warehouse ──< InventoryBalance (running quantity/transitQuantity cache, one row per item+warehouse)
Company ──< DocumentTemplate (documentType + opaque layout JSON; one default per company+documentType)  
Company ──< TransactionActionDefinition (per transactionType; ──< prerequisites (self, many-to-many), ──< allowedRoles (Role, many-to-many))
Company ──< TransactionEvent (append-only history keyed by transactionType+transactionId: CREATED / VOIDED / ACTION ──> TransactionActionDefinition)
User ──< Role (many-to-many) ──< Permission (many-to-many)
AuditLog  (append-only, references entities by type+id strings)
```

`PriceType` enum: `UNIT_PRICE`, `COST_PRICE`, `FOCAL_PRICE`, `MARKDOWN_PRICE`.

Every database constraint (Primary, Foreign, Composite, Unique, etc) should have a explicit name in the Entity class so it will be properly scripted in the database.
For example, Foreign Key name = "ITEMS_COMPANY_ID_FK"

## Key service patterns

- `PUT /items/{id}` deletes all existing SKUs and prices then re-inserts from the request. `entityManager.flush()` is called before re-insertion to release unique constraints within the same transaction.
- `ItemSku.skuCode` is globally unique across all companies; `Item.itemCode` is unique per company; `Brand.name` is unique per company; `ItemCategory.name` is unique per company.
