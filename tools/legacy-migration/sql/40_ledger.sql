-- ============================================================================================
-- 40 — Ledger and history, generated from the migrated documents exactly as each Norbiz service
-- posts them (same source types, warehouses and signs). Voided documents post nothing: legacy
-- removed a voided document's ledger rows, and a create + void pair would net to zero anyway.
-- Written with set-based SQL — not through InventoryStockService — so the "on-hand can't go below
-- zero" rule doesn't apply here (legacy history contains negative balances).
-- ============================================================================================

CREATE TABLE migration.movement (
    item_id bigint NOT NULL, warehouse_id bigint NOT NULL, quantity_delta numeric NOT NULL, transit_quantity_delta numeric NOT NULL,
    movement_date timestamptz NOT NULL, source_type text NOT NULL, source_id bigint NOT NULL,
    reference_number text NOT NULL, sheet_number text, created_at timestamptz NOT NULL, created_by text
);

-- Inventory Adjustment: signed on-hand change in its warehouse.
INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, l.quantity, 0, h.adjustment_date, 'INVENTORY_ADJUSTMENT', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM inventory_adjustments h JOIN inventory_adjustment_lines l ON l.adjustment_id = h.id WHERE NOT h.voided;

-- Stock Transfer: holds stock as negative transit in the main warehouse.
INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, 0, -l.quantity, h.transfer_date, 'STOCK_TRANSFER', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM stock_transfers h JOIN stock_transfer_lines l ON l.stock_transfer_id = h.id WHERE NOT h.voided;

-- Delivery Receipt: main on-hand out (+ releases a delivered transfer's hold); outlet transit in.
INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, -l.quantity, CASE WHEN h.stock_transfer_id IS NOT NULL THEN l.quantity ELSE 0 END,
       h.delivery_date, 'DELIVERY_RECEIPT', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM delivery_receipts h JOIN delivery_receipt_lines l ON l.delivery_receipt_id = h.id WHERE NOT h.voided;

INSERT INTO migration.movement
SELECT l.item_id, h.destination_warehouse_id, 0, l.quantity, h.delivery_date, 'DELIVERY_RECEIPT', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM delivery_receipts h JOIN delivery_receipt_lines l ON l.delivery_receipt_id = h.id
WHERE NOT h.voided AND h.destination_warehouse_id IS NOT NULL;

-- Outlet Receive: outlet transit -> on-hand.
INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, l.quantity, -l.quantity, h.receipt_date, 'OUTLET_RECEIVE', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM outlet_receives h JOIN outlet_receive_lines l ON l.outlet_receive_id = h.id WHERE NOT h.voided;

-- Outlet Delivery Receipt (outlet sale): outlet on-hand out. Return: back in.
INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, -l.quantity, 0, h.delivery_date, 'OUTLET_DELIVERY_RECEIPT', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM outlet_delivery_receipts h JOIN outlet_delivery_receipt_lines l ON l.outlet_delivery_receipt_id = h.id WHERE NOT h.voided;

INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, l.quantity, 0, h.return_date, 'OUTLET_DELIVERY_RETURN', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM outlet_delivery_returns h JOIN outlet_delivery_return_lines l ON l.outlet_delivery_return_id = h.id WHERE NOT h.voided;

-- Outlet Pull Out: outlet on-hand out, main transit in. Pull Out Receive: main transit -> on-hand.
INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, -l.quantity, 0, h.pull_out_date, 'OUTLET_PULL_OUT', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM outlet_pull_outs h JOIN outlet_pull_out_lines l ON l.outlet_pull_out_id = h.id WHERE NOT h.voided;

INSERT INTO migration.movement
SELECT l.item_id, h.destination_warehouse_id, 0, l.quantity, h.pull_out_date, 'OUTLET_PULL_OUT', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM outlet_pull_outs h JOIN outlet_pull_out_lines l ON l.outlet_pull_out_id = h.id WHERE NOT h.voided;

INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, l.quantity, -l.quantity, h.receipt_date, 'PULL_OUT_RECEIVE', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM pull_out_receives h JOIN pull_out_receive_lines l ON l.pull_out_receive_id = h.id WHERE NOT h.voided;

-- Direct Purchase Invoice: main transit in. Purchase Receive: main transit -> on-hand.
INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, 0, l.quantity, h.invoice_date, 'PURCHASE_INVOICE', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM purchase_invoices h JOIN purchase_invoice_lines l ON l.purchase_invoice_id = h.id WHERE NOT h.voided AND h.purchase_order_id IS NULL;

INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, l.quantity, -l.quantity, h.receipt_date, 'PURCHASE_RECEIVE', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM purchase_receives h JOIN purchase_receive_lines l ON l.purchase_receive_id = h.id WHERE NOT h.voided;

-- Assembly: outputs in, raw materials out, main warehouse.
INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, CASE WHEN l.kind = 'OUTPUT' THEN l.quantity ELSE -l.quantity END, 0,
       h.assembly_date, 'ASSEMBLY', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM assemblies h JOIN assembly_lines l ON l.assembly_id = h.id WHERE NOT h.voided;

-- Rows that change nothing are noise in the ledger.
DELETE FROM migration.movement WHERE quantity_delta = 0 AND transit_quantity_delta = 0;

INSERT INTO inventory_movements (company_id, item_id, warehouse_id, quantity_delta, transit_quantity_delta, movement_date,
                                 source_type, source_id, reference_number, sheet_number, notes, created_at, created_by)
SELECT migration.cid(), item_id, warehouse_id, quantity_delta, transit_quantity_delta, movement_date,
       source_type, source_id, left(reference_number, 50), sheet_number, NULL, created_at, created_by
FROM migration.movement
ORDER BY movement_date, created_at, source_type, source_id;

-- Running balances = the ledger summed (the reconciliation step corrects on-hand to legacy after).
INSERT INTO inventory_balances (item_id, warehouse_id, quantity, transit_quantity, updated_at)
SELECT item_id, warehouse_id, sum(quantity_delta), sum(transit_quantity_delta), now()
FROM migration.movement GROUP BY item_id, warehouse_id;

-- ---- transaction history: CREATED for every document, VOIDED for voided ones ----------------

INSERT INTO transaction_events (company_id, transaction_type, transaction_id, reference_number, event_type, performed_by, performed_at, remarks)
SELECT migration.cid(), t.type, t.id, left(t.reference_number, 50), 'CREATED', coalesce(t.created_by, 'legacy-migration'), t.created_at,
       CASE t.origin WHEN 'RECONSTRUCTED' THEN 'Reconstructed by the legacy migration' ELSE 'Migrated from legacy' END
FROM (
    SELECT 'INVENTORY_ADJUSTMENT' type, id, reference_number, created_by, created_at, origin FROM inventory_adjustments
    UNION ALL SELECT 'STOCK_TRANSFER', id, reference_number, created_by, created_at, origin FROM stock_transfers
    UNION ALL SELECT 'DELIVERY_RECEIPT', id, reference_number, created_by, created_at, origin FROM delivery_receipts
    UNION ALL SELECT 'OUTLET_RECEIVE', id, reference_number, created_by, created_at, origin FROM outlet_receives
    UNION ALL SELECT 'OUTLET_DELIVERY_RECEIPT', id, reference_number, created_by, created_at, origin FROM outlet_delivery_receipts
    UNION ALL SELECT 'OUTLET_DELIVERY_RETURN', id, reference_number, created_by, created_at, origin FROM outlet_delivery_returns
    UNION ALL SELECT 'OUTLET_PULL_OUT', id, reference_number, created_by, created_at, origin FROM outlet_pull_outs
    UNION ALL SELECT 'PULL_OUT_RECEIVE', id, reference_number, created_by, created_at, origin FROM pull_out_receives
    UNION ALL SELECT 'PURCHASE_INVOICE', id, reference_number, created_by, created_at, origin FROM purchase_invoices
    UNION ALL SELECT 'PURCHASE_RECEIVE', id, reference_number, created_by, created_at, origin FROM purchase_receives
    UNION ALL SELECT 'ASSEMBLY', id, reference_number, created_by, created_at, origin FROM assemblies
) t;

INSERT INTO transaction_events (company_id, transaction_type, transaction_id, reference_number, event_type, performed_by, performed_at, remarks)
SELECT migration.cid(), t.type, t.id, left(t.reference_number, 50), 'VOIDED', coalesce(t.voided_by, 'legacy-migration'), t.voided_at, NULL
FROM (
    SELECT 'INVENTORY_ADJUSTMENT' type, id, reference_number, voided, voided_by, voided_at FROM inventory_adjustments
    UNION ALL SELECT 'STOCK_TRANSFER', id, reference_number, voided, voided_by, voided_at FROM stock_transfers
    UNION ALL SELECT 'DELIVERY_RECEIPT', id, reference_number, voided, voided_by, voided_at FROM delivery_receipts
    UNION ALL SELECT 'OUTLET_RECEIVE', id, reference_number, voided, voided_by, voided_at FROM outlet_receives
    UNION ALL SELECT 'OUTLET_DELIVERY_RECEIPT', id, reference_number, voided, voided_by, voided_at FROM outlet_delivery_receipts
    UNION ALL SELECT 'OUTLET_DELIVERY_RETURN', id, reference_number, voided, voided_by, voided_at FROM outlet_delivery_returns
    UNION ALL SELECT 'OUTLET_PULL_OUT', id, reference_number, voided, voided_by, voided_at FROM outlet_pull_outs
    UNION ALL SELECT 'PULL_OUT_RECEIVE', id, reference_number, voided, voided_by, voided_at FROM pull_out_receives
    UNION ALL SELECT 'PURCHASE_INVOICE', id, reference_number, voided, voided_by, voided_at FROM purchase_invoices
    UNION ALL SELECT 'PURCHASE_RECEIVE', id, reference_number, voided, voided_by, voided_at FROM purchase_receives
    UNION ALL SELECT 'ASSEMBLY', id, reference_number, voided, voided_by, voided_at FROM assemblies
) t WHERE t.voided;
