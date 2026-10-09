# Entity Model & Key Service Patterns

## Entity relationships

```
Company ──< User (many-to-many via user_companies)
Company ──< Brand
Company ──< Employee (optional link to User)
Company ──< Warehouse
Company ──< Supplier
Company ──< Customer (type: CUSTOMER | OUTLET)
Company ──< Item ──< ItemSku (item optional: a SKU may stand alone, scoped by its own company)
                └──< ItemPrice (one row per PriceType enum value)
                └──> ItemCategory (unique name per company)
                └──> ItemGroup (optional; unique name per company)
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

- `POST`/`PUT /items` take the item's SKUs as `skuLines: [{skuCode, unitPrice}]`, reconciled by code: existing codes keep their row and get the new unit price, codes missing from the list are deleted (orphan removal), new codes are inserted (a code may also be used by other items; it is rejected only if listed twice for the same item). Omitting `skuLines` (null) leaves the SKUs untouched. The response carries both `skus` (codes) and `skuLines`. Prices are still deleted and re-inserted; `entityManager.flush()` runs between the two so `ITEM_PRICES_ITEM_PRICE_TYPE_UQ` doesn't fire.
- `ItemSku.skuCode` is unique per item only (several items may share a code, as legacy department-store SKUs do); `Item.itemCode` is unique per company; `Brand.name` is unique per company; `ItemCategory.name` is unique per company; `ItemGroup.name` is unique per company.
- `ItemSku` carries its own `company` (equal to the item's when it has one); `item` is optional, so a SKU can exist unassigned (legacy price points that matched no item, or inactive ones). Company access is checked through `ItemSku.company`. Besides code and unit price it holds the legacy price point columns: `itemCategory`, `brand` (same company), `priceType` (`UNIT_PRICE` = regular or `FOCAL_PRICE`), `storeItemCode`, `barcode`, `vendorPart`, `rdsDescription`, `active`, `rdsSku`, `landmarkSku`. `POST /item-skus` takes `itemId` or, without one, `companyId`. On `PUT`, a null optional field is left unchanged and a blank string clears a text field; the item and company can't be changed. SKUs are usually looked up by brand, then category, then price: `GET /item-skus?brandId=&itemCategoryId=&price=` are exact filters backed by the index `ITEM_SKUS_BRAND_CATEGORY_PRICE_IX (company_id, brand_id, item_category_id, unit_price)`. `GET /item-skus` also filters by `itemCategory`, `brand` (names, contains), `priceType`, `barcode`, `storeItemCode`, `active`, and `assigned` (true/false = with/without an item).
