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
FROM inventory_adjustments h JOIN inventory_adjustment_lines l ON l.adjustment_id = h.id WHERE NOT h.voided AND h.company_id = migration.cid();

-- Stock Transfer: holds stock as negative transit in the main warehouse.
INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, 0, -l.quantity, h.transfer_date, 'STOCK_TRANSFER', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM stock_transfers h JOIN stock_transfer_lines l ON l.stock_transfer_id = h.id WHERE NOT h.voided AND h.company_id = migration.cid();

-- Delivery Receipt: main on-hand out (+ releases a delivered transfer's hold); outlet transit in.
INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, -l.quantity, CASE WHEN h.stock_transfer_id IS NOT NULL THEN l.quantity ELSE 0 END,
       h.delivery_date, 'DELIVERY_RECEIPT', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM delivery_receipts h JOIN delivery_receipt_lines l ON l.delivery_receipt_id = h.id WHERE NOT h.voided AND h.company_id = migration.cid();

INSERT INTO migration.movement
SELECT l.item_id, h.destination_warehouse_id, 0, l.quantity, h.delivery_date, 'DELIVERY_RECEIPT', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM delivery_receipts h JOIN delivery_receipt_lines l ON l.delivery_receipt_id = h.id
WHERE NOT h.voided AND h.company_id = migration.cid() AND h.destination_warehouse_id IS NOT NULL;

-- Outlet Receipt: outlet transit -> on-hand.
INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, l.quantity, -l.quantity, h.receipt_date, 'OUTLET_RECEIPT', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM outlet_receipts h JOIN outlet_receipt_lines l ON l.outlet_receipt_id = h.id WHERE NOT h.voided AND h.company_id = migration.cid();

-- Outlet Delivery Receipt (outlet sale): outlet on-hand out. Return: back in.
INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, -l.quantity, 0, h.delivery_date, 'OUTLET_DELIVERY_RECEIPT', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM outlet_delivery_receipts h JOIN outlet_delivery_receipt_lines l ON l.outlet_delivery_receipt_id = h.id WHERE NOT h.voided AND h.company_id = migration.cid();

INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, l.quantity, 0, h.return_date, 'OUTLET_DELIVERY_RETURN', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM outlet_delivery_returns h JOIN outlet_delivery_return_lines l ON l.outlet_delivery_return_id = h.id WHERE NOT h.voided AND h.company_id = migration.cid();

-- Outlet Pull-out: outlet on-hand out, main transit in. Pull-out Receipt: main transit -> on-hand.
INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, -l.quantity, 0, h.pull_out_date, 'OUTLET_PULL_OUT', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM outlet_pull_outs h JOIN outlet_pull_out_lines l ON l.outlet_pull_out_id = h.id WHERE NOT h.voided AND h.company_id = migration.cid();

INSERT INTO migration.movement
SELECT l.item_id, h.destination_warehouse_id, 0, l.quantity, h.pull_out_date, 'OUTLET_PULL_OUT', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM outlet_pull_outs h JOIN outlet_pull_out_lines l ON l.outlet_pull_out_id = h.id WHERE NOT h.voided AND h.company_id = migration.cid();

INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, l.quantity, -l.quantity, h.receipt_date, 'PULL_OUT_RECEIPT', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM pull_out_receipts h JOIN pull_out_receipt_lines l ON l.pull_out_receipt_id = h.id WHERE NOT h.voided AND h.company_id = migration.cid();

-- Direct Purchase Invoice: main transit in. Purchase Receipt: main transit -> on-hand.
INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, 0, l.quantity, h.invoice_date, 'PURCHASE_INVOICE', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM purchase_invoices h JOIN purchase_invoice_lines l ON l.purchase_invoice_id = h.id WHERE NOT h.voided AND h.company_id = migration.cid() AND h.purchase_order_id IS NULL;

INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, l.quantity, -l.quantity, h.receipt_date, 'PURCHASE_RECEIPT', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM purchase_receipts h JOIN purchase_receipt_lines l ON l.purchase_receipt_id = h.id WHERE NOT h.voided AND h.company_id = migration.cid();

-- Assembly: outputs in, raw materials out, main warehouse.
INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, CASE WHEN l.kind = 'OUTPUT' THEN l.quantity ELSE -l.quantity END, 0,
       h.assembly_date, 'ASSEMBLY', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM assemblies h JOIN assembly_lines l ON l.assembly_id = h.id WHERE NOT h.voided AND h.company_id = migration.cid();

-- Direct Sales Invoice: main on-hand out — only the lines legacy posted to stock (30_sales.sql).
INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, -l.quantity, 0, h.invoice_date, 'SALES_INVOICE', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM sales_invoices h JOIN sales_invoice_lines l ON l.sales_invoice_id = h.id
WHERE h.company_id = migration.cid() AND h.origin = 'MIGRATED' AND NOT h.voided
  AND EXISTS (SELECT 1 FROM migration.si_posted p WHERE p.invoice_id = h.id AND p.item_id = l.item_id);

-- Sales Invoice Return: main on-hand back in — only the lines legacy posted to stock (30_sales.sql).
INSERT INTO migration.movement
SELECT l.item_id, h.warehouse_id, l.quantity, 0, h.return_date, 'SALES_INVOICE_RETURN', h.id, h.reference_number, h.sheet_number, h.created_at, h.created_by
FROM sales_invoice_returns h JOIN sales_invoice_return_lines l ON l.sales_invoice_return_id = h.id
WHERE h.company_id = migration.cid() AND h.origin = 'MIGRATED' AND NOT h.voided AND l.posts_stock;

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

CREATE TABLE migration.document AS
SELECT * FROM (
    SELECT 'INVENTORY_ADJUSTMENT' type, id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM inventory_adjustments
    UNION ALL SELECT 'STOCK_TRANSFER', id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM stock_transfers
    UNION ALL SELECT 'DELIVERY_RECEIPT', id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM delivery_receipts
    UNION ALL SELECT 'OUTLET_RECEIPT', id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM outlet_receipts
    UNION ALL SELECT 'OUTLET_DELIVERY_RECEIPT', id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM outlet_delivery_receipts
    UNION ALL SELECT 'OUTLET_DELIVERY_RETURN', id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM outlet_delivery_returns
    UNION ALL SELECT 'OUTLET_PULL_OUT', id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM outlet_pull_outs
    UNION ALL SELECT 'PULL_OUT_RECEIPT', id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM pull_out_receipts
    UNION ALL SELECT 'PURCHASE_INVOICE', id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM purchase_invoices
    UNION ALL SELECT 'PURCHASE_RECEIPT', id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM purchase_receipts
    UNION ALL SELECT 'ASSEMBLY', id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM assemblies
    UNION ALL SELECT 'SALES_INVOICE', id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM sales_invoices
    UNION ALL SELECT 'SALES_INVOICE_RETURN', id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM sales_invoice_returns
    UNION ALL SELECT 'CUSTOMER_MEMO', id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM customer_memos
    UNION ALL SELECT 'CUSTOMER_PAYMENT', id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM customer_payments
    UNION ALL SELECT 'CUSTOMER_REFUND', id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM customer_refunds
    UNION ALL SELECT 'DEPOSIT', id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM deposits
    UNION ALL SELECT 'SUPPLIER_PAYMENT', id, company_id, reference_number, created_by, created_at, origin, voided, voided_by, voided_at FROM supplier_payments
) t WHERE t.company_id = migration.cid() AND t.origin IN ('MIGRATED', 'RECONSTRUCTED');

INSERT INTO transaction_events (company_id, transaction_type, transaction_id, reference_number, event_type, performed_by, performed_at, remarks)
SELECT migration.cid(), t.type, t.id, left(t.reference_number, 50), 'CREATED', coalesce(t.created_by, 'legacy-migration'), t.created_at,
       CASE t.origin WHEN 'RECONSTRUCTED' THEN 'Reconstructed by the legacy migration' ELSE 'Migrated from legacy' END
FROM migration.document t;

INSERT INTO transaction_events (company_id, transaction_type, transaction_id, reference_number, event_type, performed_by, performed_at, remarks)
SELECT migration.cid(), t.type, t.id, left(t.reference_number, 50), 'VOIDED', coalesce(t.voided_by, 'legacy-migration'), t.voided_at, NULL
FROM migration.document t WHERE t.voided;

-- ---- Stock Transfer actions (sign-offs) <- tblStockTransferUserActions ----------------------
-- One action definition per distinct legacy ActionId, then one ACTION history event per legacy
-- row. Legacy UserId is an employee ID. Every action allows only SUPER_ADMIN, as a placeholder:
-- the real roles are assigned after cutover. There are no prerequisites, because legacy didn't
-- enforce an order. A blank ActionId becomes an inactive UNSPECIFIED action, so its history is
-- kept without showing a button for it.

CREATE TABLE migration.st_action AS
SELECT a.legacy_action,
       coalesce(nullif(upper(btrim(regexp_replace(a.legacy_action, '[^A-Za-z0-9]+', '_', 'g'), '_')), ''), 'UNSPECIFIED') AS code,
       coalesce(nullif(initcap(a.legacy_action), ''), 'Unspecified (legacy)') AS name,
       a.first_at
FROM (SELECT btrim(actionid) AS legacy_action, min(migration.at(dateencoded)) AS first_at
      FROM legacy.tblstocktransferuseractions GROUP BY 1) a;

-- Typical legacy order; an action legacy adds later sorts after these, by name.
INSERT INTO transaction_action_definitions (company_id, transaction_type, code, name, sort_order, active,
                                            created_at, updated_at, created_by, updated_by)
SELECT migration.cid(), 'STOCK_TRANSFER', a.code, migration.trunc(a.name, 255),
       coalesce(o.sort_order, 100 + row_number() OVER (ORDER BY o.sort_order NULLS FIRST, a.name)::int),
       a.code <> 'UNSPECIFIED', coalesce(a.first_at, now()), now(), 'legacy-migration', 'legacy-migration'
FROM migration.st_action a
LEFT JOIN (VALUES ('ENCODED_BY', 10), ('ISSUED_BY', 20), ('PRICING', 30), ('BARCODE_PRINTING', 40),
                  ('BARCODE_DISTRIBUTION', 50), ('PRODUCTION', 60), ('QUALITY_CONTROL', 70), ('UNSPECIFIED', 999))
     o (code, sort_order) ON o.code = a.code;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM roles WHERE name = 'SUPER_ADMIN') THEN
        RAISE EXCEPTION 'Role SUPER_ADMIN is missing: start Norbiz once so DataInitializer seeds it';
    END IF;
END $$;

INSERT INTO transaction_action_definition_roles (action_definition_id, role_id)
SELECT d.id, r.id FROM transaction_action_definitions d CROSS JOIN roles r
WHERE d.company_id = migration.cid() AND d.transaction_type = 'STOCK_TRANSFER' AND r.name = 'SUPER_ADMIN';

INSERT INTO transaction_events (company_id, transaction_type, transaction_id, reference_number, event_type,
                                action_definition_id, action_code, action_name, performed_by, performed_at, remarks)
SELECT migration.cid(), 'STOCK_TRANSFER', s.id, left(s.reference_number, 50), 'ACTION',
       d.id, d.code, d.name, migration.actor(u.userid), coalesce(migration.at(u.dateencoded), s.created_at),
       migration.trunc(regexp_replace(u.comment, '\s+', ' ', 'g'), 255)
FROM legacy.tblstocktransferuseractions u
JOIN stock_transfers s ON s.id = u.masterid
JOIN migration.st_action a ON a.legacy_action = btrim(u.actionid)
JOIN transaction_action_definitions d ON d.company_id = migration.cid() AND d.transaction_type = 'STOCK_TRANSFER' AND d.code = a.code
ORDER BY u.id;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'truncated', 'StockTransferAction', id, 'Comment of ' || length(btrim(regexp_replace(comment, '\s+', ' ', 'g'))) || ' characters cut to 255'
FROM legacy.tblstocktransferuseractions WHERE length(btrim(regexp_replace(comment, '\s+', ' ', 'g'))) > 255
UNION ALL
SELECT 'unspecified', 'StockTransferAction', id, 'Blank legacy action on transfer ' || masterid || '; recorded as UNSPECIFIED'
FROM legacy.tblstocktransferuseractions WHERE btrim(actionid) = ''
UNION ALL
SELECT 'skipped', 'StockTransferAction', u.id, 'Transfer ' || u.masterid || ' was not migrated'
FROM legacy.tblstocktransferuseractions u WHERE NOT EXISTS (SELECT 1 FROM stock_transfers s WHERE s.id = u.masterid);
