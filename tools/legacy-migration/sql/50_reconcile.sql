-- ============================================================================================
-- 50 — Reconcile on-hand with the legacy stock balance (tblInventory) at cutover.
--
-- The documents reproduce the legacy ledger almost exactly, but legacy's stored balances disagree
-- with its own ledger for a few thousand item/warehouse pairs. tblInventory is what legacy users
-- see as stock, so on-hand is aligned to it: one RECONSTRUCTED Inventory Adjustment per warehouse,
-- dated the cutover day, with a line per item that differs. Transit differences can't be posted
-- by an adjustment; they are listed by report.sql instead.
-- ============================================================================================

CREATE TABLE migration.onhand_before AS
SELECT coalesce(n.warehouse_id, l.warehouseid) AS warehouse_id, coalesce(n.item_id, l.itemid) AS item_id,
       coalesce(n.quantity, 0) AS norbiz, coalesce(round(l.quantity::numeric, 4), 0) AS legacy,
       coalesce(n.transit_quantity, 0) AS norbiz_transit, coalesce(round(l.intransitqty::numeric, 4), 0) AS legacy_transit
FROM inventory_balances n
FULL JOIN legacy.tblinventory l ON l.warehouseid = n.warehouse_id AND l.itemid = n.item_id;

CREATE TABLE migration.correction AS
SELECT warehouse_id, item_id, legacy - norbiz AS quantity
FROM migration.onhand_before WHERE abs(legacy - norbiz) > 0.00005;

CREATE TABLE migration.correction_adjustment AS
SELECT warehouse_id,
       (SELECT max(id) FROM inventory_adjustments) + row_number() OVER (ORDER BY warehouse_id) AS adjustment_id,
       (SELECT count(*) FROM inventory_adjustments WHERE reference_number LIKE 'RCN-IA-%') + row_number() OVER (ORDER BY warehouse_id) AS n
FROM (SELECT DISTINCT warehouse_id FROM migration.correction) x;

INSERT INTO inventory_adjustments (id, company_id, warehouse_id, reference_number, sheet_number, adjustment_date, reason,
                                   created_at, created_by, voided, voided_at, voided_by, loaded, origin)
SELECT a.adjustment_id, migration.cid(), a.warehouse_id, 'RCN-IA-' || lpad(a.n::text, 6, '0'), NULL,
       ((SELECT cutover FROM migration.ctx)::date)::timestamp AT TIME ZONE 'UTC',
       'Reconstructed: aligns on-hand with the legacy stock balance at cutover (legacy ledger and balance disagreed)',
       (SELECT cutover FROM migration.ctx), (SELECT actor FROM migration.ctx), false, NULL, NULL, false, 'RECONSTRUCTED'
FROM migration.correction_adjustment a;

INSERT INTO inventory_adjustment_lines (id, adjustment_id, item_id, quantity, quantity_loaded, line_number)
SELECT (SELECT max(id) FROM inventory_adjustment_lines) + row_number() OVER (ORDER BY a.adjustment_id, c.item_id),
       a.adjustment_id, c.item_id, c.quantity, 0, row_number() OVER (PARTITION BY a.adjustment_id ORDER BY c.item_id)
FROM migration.correction c JOIN migration.correction_adjustment a ON a.warehouse_id = c.warehouse_id;

INSERT INTO inventory_movements (company_id, item_id, warehouse_id, quantity_delta, transit_quantity_delta, movement_date,
                                 source_type, source_id, reference_number, sheet_number, notes, created_at, created_by)
SELECT migration.cid(), l.item_id, h.warehouse_id, l.quantity, 0, h.adjustment_date, 'INVENTORY_ADJUSTMENT', h.id,
       h.reference_number, NULL, NULL, h.created_at, h.created_by
FROM inventory_adjustments h JOIN inventory_adjustment_lines l ON l.adjustment_id = h.id
WHERE h.id IN (SELECT adjustment_id FROM migration.correction_adjustment);

INSERT INTO inventory_balances (item_id, warehouse_id, quantity, transit_quantity, updated_at)
SELECT item_id, warehouse_id, quantity, 0, now() FROM migration.correction
ON CONFLICT (item_id, warehouse_id) DO UPDATE SET quantity = inventory_balances.quantity + EXCLUDED.quantity, updated_at = now();

INSERT INTO transaction_events (company_id, transaction_type, transaction_id, reference_number, event_type, performed_by, performed_at, remarks)
SELECT migration.cid(), 'INVENTORY_ADJUSTMENT', h.id, h.reference_number, 'CREATED', h.created_by, h.created_at, 'Reconstructed by the legacy migration'
FROM inventory_adjustments h WHERE h.id IN (SELECT adjustment_id FROM migration.correction_adjustment);
