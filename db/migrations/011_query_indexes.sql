-- Indexes for the access paths the app actually uses. Postgres doesn't index foreign keys on its own, and prod
-- (6.2M inventory movements, ~1M lines per document type) was answering every "lines of this document", "movements
-- of this item" and dashboard query with a full table scan — one item's movement history took 10.5 s.
--
-- Plain CREATE INDEX (not CONCURRENTLY): migrations run in a transaction while the app is stopped. On prod's
-- volume the whole file builds in roughly a minute or two. IF NOT EXISTS keeps it idempotent (init.sql has the same).

-- Inventory ledger: movement history / balance-as-of filtered by item, warehouse or company, plus a date range.
CREATE INDEX IF NOT EXISTS INVENTORY_MOVEMENTS_ITEM_WAREHOUSE_DATE_IDX ON inventory_movements (item_id, warehouse_id, movement_date);
CREATE INDEX IF NOT EXISTS INVENTORY_MOVEMENTS_WAREHOUSE_DATE_IDX ON inventory_movements (warehouse_id, movement_date);
CREATE INDEX IF NOT EXISTS INVENTORY_MOVEMENTS_COMPANY_DATE_IDX ON inventory_movements (company_id, movement_date);
-- The (item_id, warehouse_id) unique index can't serve warehouse-only lookups (stock per warehouse, outlet on-hand).
CREATE INDEX IF NOT EXISTS INVENTORY_BALANCES_WAREHOUSE_IDX ON inventory_balances (warehouse_id);

-- Lines -> their document (loading a document, voiding, FK checks when a header is deleted).
CREATE INDEX IF NOT EXISTS INVENTORY_ADJUSTMENT_LINES_ADJUSTMENT_IDX ON inventory_adjustment_lines (adjustment_id);
CREATE INDEX IF NOT EXISTS PURCHASE_ORDER_LINES_ORDER_IDX ON purchase_order_lines (purchase_order_id);
CREATE INDEX IF NOT EXISTS PURCHASE_INVOICE_LINES_INVOICE_IDX ON purchase_invoice_lines (purchase_invoice_id);
CREATE INDEX IF NOT EXISTS PURCHASE_INVOICE_LINES_ORDER_LINE_IDX ON purchase_invoice_lines (purchase_order_line_id);
CREATE INDEX IF NOT EXISTS PURCHASE_RECEIPT_LINES_RECEIPT_IDX ON purchase_receipt_lines (purchase_receipt_id);
CREATE INDEX IF NOT EXISTS PURCHASE_RECEIPT_LINES_ORDER_LINE_IDX ON purchase_receipt_lines (purchase_order_line_id);
CREATE INDEX IF NOT EXISTS PURCHASE_RECEIPT_LINES_INVOICE_LINE_IDX ON purchase_receipt_lines (purchase_invoice_line_id);
CREATE INDEX IF NOT EXISTS DELIVERY_RECEIPT_LINES_RECEIPT_IDX ON delivery_receipt_lines (delivery_receipt_id);
CREATE INDEX IF NOT EXISTS OUTLET_RECEIPT_LINES_RECEIPT_IDX ON outlet_receipt_lines (outlet_receipt_id);
CREATE INDEX IF NOT EXISTS OUTLET_RECEIPT_LINES_DR_LINE_IDX ON outlet_receipt_lines (delivery_receipt_line_id);
CREATE INDEX IF NOT EXISTS OUTLET_DELIVERY_RECEIPT_LINES_RECEIPT_IDX ON outlet_delivery_receipt_lines (outlet_delivery_receipt_id);
CREATE INDEX IF NOT EXISTS OUTLET_DELIVERY_RETURN_LINES_RETURN_IDX ON outlet_delivery_return_lines (outlet_delivery_return_id);
CREATE INDEX IF NOT EXISTS OUTLET_DELIVERY_RETURN_LINES_ODR_LINE_IDX ON outlet_delivery_return_lines (outlet_delivery_receipt_line_id);
CREATE INDEX IF NOT EXISTS STOCK_TRANSFER_LINES_TRANSFER_IDX ON stock_transfer_lines (stock_transfer_id);
CREATE INDEX IF NOT EXISTS OUTLET_PULL_OUT_LINES_PULL_OUT_IDX ON outlet_pull_out_lines (outlet_pull_out_id);
CREATE INDEX IF NOT EXISTS PULL_OUT_RECEIPT_LINES_RECEIPT_IDX ON pull_out_receipt_lines (pull_out_receipt_id);
CREATE INDEX IF NOT EXISTS PULL_OUT_RECEIPT_LINES_PULL_OUT_LINE_IDX ON pull_out_receipt_lines (outlet_pull_out_line_id);
CREATE INDEX IF NOT EXISTS BILL_OF_MATERIAL_LINES_BOM_IDX ON bill_of_material_lines (bill_of_material_id);
CREATE INDEX IF NOT EXISTS ASSEMBLY_LINES_ASSEMBLY_IDX ON assembly_lines (assembly_id);
CREATE INDEX IF NOT EXISTS SALES_INVOICE_LINES_INVOICE_IDX ON sales_invoice_lines (sales_invoice_id);
CREATE INDEX IF NOT EXISTS SALES_INVOICE_LINES_DR_LINE_IDX ON sales_invoice_lines (delivery_receipt_line_id);
CREATE INDEX IF NOT EXISTS CUSTOMER_MEMO_LINES_MEMO_IDX ON customer_memo_lines (customer_memo_id);
CREATE INDEX IF NOT EXISTS SUPPLIER_MEMO_LINES_MEMO_IDX ON supplier_memo_lines (supplier_memo_id);
CREATE INDEX IF NOT EXISTS CUSTOMER_PAYMENT_TENDERS_PAYMENT_IDX ON customer_payment_tenders (customer_payment_id);
CREATE INDEX IF NOT EXISTS CUSTOMER_PAYMENT_TENDERS_DEPOSIT_IDX ON customer_payment_tenders (deposit_id);
CREATE INDEX IF NOT EXISTS SUPPLIER_PAYMENT_TENDERS_PAYMENT_IDX ON supplier_payment_tenders (supplier_payment_id);
CREATE INDEX IF NOT EXISTS DEPOSIT_LINES_DEPOSIT_IDX ON deposit_lines (deposit_id);
CREATE INDEX IF NOT EXISTS DEPOSIT_LINES_TENDER_IDX ON deposit_lines (tender_id);
CREATE INDEX IF NOT EXISTS CUSTOMER_REFUND_LINES_REFUND_IDX ON customer_refund_lines (customer_refund_id);
CREATE INDEX IF NOT EXISTS SUPPLIER_REFUND_LINES_REFUND_IDX ON supplier_refund_lines (supplier_refund_id);
CREATE INDEX IF NOT EXISTS STATEMENT_OF_ACCOUNT_LINES_SOA_IDX ON statement_of_account_lines (statement_of_account_id);
CREATE INDEX IF NOT EXISTS BANK_RECONCILIATION_LINES_RECON_IDX ON bank_reconciliation_lines (bank_reconciliation_id);

-- Document -> the document it was created from.
CREATE INDEX IF NOT EXISTS OUTLET_RECEIPTS_DELIVERY_RECEIPT_IDX ON outlet_receipts (delivery_receipt_id);
CREATE INDEX IF NOT EXISTS DELIVERY_RECEIPTS_STOCK_TRANSFER_IDX ON delivery_receipts (stock_transfer_id);
CREATE INDEX IF NOT EXISTS PULL_OUT_RECEIPTS_PULL_OUT_IDX ON pull_out_receipts (outlet_pull_out_id);

-- Documents by company and business date (dashboard periods, date-range list filters, detailed reports).
CREATE INDEX IF NOT EXISTS INVENTORY_ADJUSTMENTS_COMPANY_DATE_IDX ON inventory_adjustments (company_id, adjustment_date);
CREATE INDEX IF NOT EXISTS PURCHASE_ORDERS_COMPANY_DATE_IDX ON purchase_orders (company_id, order_date);
CREATE INDEX IF NOT EXISTS PURCHASE_INVOICES_COMPANY_DATE_IDX ON purchase_invoices (company_id, invoice_date);
CREATE INDEX IF NOT EXISTS PURCHASE_RECEIPTS_COMPANY_DATE_IDX ON purchase_receipts (company_id, receipt_date);
CREATE INDEX IF NOT EXISTS DELIVERY_RECEIPTS_COMPANY_DATE_IDX ON delivery_receipts (company_id, delivery_date);
CREATE INDEX IF NOT EXISTS OUTLET_RECEIPTS_COMPANY_DATE_IDX ON outlet_receipts (company_id, receipt_date);
CREATE INDEX IF NOT EXISTS OUTLET_DELIVERY_RECEIPTS_COMPANY_DATE_IDX ON outlet_delivery_receipts (company_id, delivery_date);
CREATE INDEX IF NOT EXISTS OUTLET_DELIVERY_RETURNS_COMPANY_DATE_IDX ON outlet_delivery_returns (company_id, return_date);
CREATE INDEX IF NOT EXISTS STOCK_TRANSFERS_COMPANY_DATE_IDX ON stock_transfers (company_id, transfer_date);
CREATE INDEX IF NOT EXISTS OUTLET_PULL_OUTS_COMPANY_DATE_IDX ON outlet_pull_outs (company_id, pull_out_date);
CREATE INDEX IF NOT EXISTS PULL_OUT_RECEIPTS_COMPANY_DATE_IDX ON pull_out_receipts (company_id, receipt_date);
CREATE INDEX IF NOT EXISTS ASSEMBLIES_COMPANY_DATE_IDX ON assemblies (company_id, assembly_date);
CREATE INDEX IF NOT EXISTS TRANSACTION_EVENTS_COMPANY_PERFORMED_IDX ON transaction_events (company_id, performed_at);

-- Backlogs: the few documents still waiting to be received (pending-* dashboard widgets, receivable lookups).
CREATE INDEX IF NOT EXISTS DELIVERY_RECEIPTS_PENDING_IDX ON delivery_receipts (company_id) WHERE NOT voided AND NOT loaded;
CREATE INDEX IF NOT EXISTS OUTLET_PULL_OUTS_PENDING_IDX ON outlet_pull_outs (company_id) WHERE NOT voided AND NOT loaded;
CREATE INDEX IF NOT EXISTS PURCHASE_ORDERS_PENDING_IDX ON purchase_orders (company_id) WHERE NOT voided AND NOT loaded;
