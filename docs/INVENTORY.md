# Inventory Management

- Norbiz tracks the inventory level of items throughout different warehouses (storage locations). 
- Different transactions dictate the item count in each warehouse whether it increases or decreases
- Reporting of inventory movement will be tracked by As of Date, within date range or the current count in each warehouse. 
- Inventory Balance report should show the current value of the item per warehouse within a time period. Further drilling this report should forward to the Inventory Ledger report
- The Inventory Ledger report will provide all the transactions that contributed to the Inventory Balance
- Quantity is referred to as main stock level of an item in a warehouse. Transit Quantity is a count that are not yet added or subtracted from the main stock
- Transit Quantity is posted by transactions that have not been fully received or dispatched by Norbiz. Example of this are, purchase ordered items but have not received by the warehouse. Implemented: `PurchaseOrder` posts `transitQuantityDelta = +quantity` per line on creation (leaving `quantity` untouched), and reverses it (`-quantity`) on void — see `docs/TRANSACTIONS.md`. A "Direct" `PurchaseInvoice` (no backing PO) posts the same way; a PO-based `PurchaseInvoice` doesn't — it loads the PO's existing transit posting instead. A `DeliveryReceipt` to an OUTLET customer deducts `quantity` in the company's main warehouse and posts `transitQuantityDelta = +quantity` in the outlet's own warehouse; `OutletReceive` then moves it to on-hand there (`quantityDelta = +q`, `transitQuantityDelta = -q`). A Delivery Receipt to a plain customer only deducts main-warehouse `quantity`. An `OutletDeliveryReceipt` (outlet sale) deducts `quantity` in the outlet's warehouse; an `OutletDeliveryReturn` adds it back. Neither touches transit.
- Every inventory transaction should commit their Post Transaction Company Id, Warehouse Id, ItemId, Source Type, Reference Number, Sheet Number, Posting Date, Posted By data to the Inventory Movement table

## Negative stock
- **On-hand `quantity` can never go below zero** in any warehouse. Any posting — a create *or* a void — whose `quantityDelta` would take an item's balance below zero is rejected and the whole transaction rolls back. A posting that exactly drains stock to zero is allowed. Positive deltas are always allowed (even onto a pre-existing negative balance, which can only move it toward zero). Transit quantity is not checked by this rule.
- What it blocks in practice: an Inventory Adjustment with a negative line beyond on-hand; a Delivery Receipt beyond main-warehouse on-hand; an Outlet Delivery Receipt beyond outlet on-hand; and voiding a stock-*adding* transaction (positive adjustment line, Purchase Receive, Outlet Receive, Outlet Delivery Return) once that stock has since been consumed.
- Implementation: every transaction service changes balances only through `InventoryStockService.apply(...)`, which locks the `InventoryBalance` row (`PESSIMISTIC_WRITE`, so concurrent postings on the same item/warehouse serialize) and enforces the rule. Services that take stock out also call `InventoryStockService.assertAvailable(...)` first — it sums lines per item and reports **every** short item at once, and on create runs before the reference number is generated so a rejected request doesn't burn one. New transaction types must post through `apply` and add the matching `assertAvailable` pre-check.
- Error contract: `InsufficientStockException` → **409**, `code: "INSUFFICIENT_STOCK"`, `details: {shortfalls: [{itemId, itemCode, itemName, warehouseId, warehouseName, available, required}]}`. The frontend mirrors the rule on create forms (On Hand guide column flags short lines, summed per item, and blocks Post) and shows the backend message for void/post rejections.

## Reports
- Reports special queries that users generate. 
- The main categories are: Inventory, Purchases, Sales (for now)
- Every transaction type also has a `<Transaction> - Detailed` line-item report with its own permission — see `docs/TRANSACTIONS.md` → `## Detailed reports`.
