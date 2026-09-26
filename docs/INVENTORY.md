# Inventory Management

- Norbiz tracks the inventory level of items throughout different warehouses (storage locations). 
- Different transactions dictate the item count in each warehouse whether it increases or decreases
- Reporting of inventory movement will be tracked by As of Date, within date range or the current count in each warehouse. 
- Inventory Balance report should show the current value of the item per warehouse within a time period. Further drilling this report should forward to the Inventory Ledger report
- The Inventory Ledger report will provide all the transactions that contributed to the Inventory Balance
- Quantity is referred to as main stock level of an item in a warehouse. Transit Quantity is a count that are not yet added or subtracted from the main stock
- Transit Quantity is posted by transactions that have not been fully received or dispatched by Norbiz. Example of this are, purchase ordered items but have not received by the warehouse. Implemented: `PurchaseOrder` posts `transitQuantityDelta = +quantity` per line on creation (leaving `quantity` untouched), and reverses it (`-quantity`) on void — see `docs/TRANSACTIONS.md`. A "Direct" `PurchaseInvoice` (no backing PO) posts the same way; a PO-based `PurchaseInvoice` doesn't — it loads the PO's existing transit posting instead.
- Every inventory transaction should commit their Post Transaction Company Id, Warehouse Id, ItemId, Source Type, Reference Number, Sheet Number, Posting Date, Posted By data to the Inventory Movement table

## Reports
- Reports special queries that users generate. 
- The main categories are: Inventory, Purchases, Sales (for now)
