-- ============================================================================================
-- 20 — Transactions: every legacy document becomes the matching Norbiz transaction (origin
-- MIGRATED, keeping its legacy reference/sheet number), plus RECONSTRUCTED documents where the
-- legacy flow skipped a step Norbiz requires. Stock movements are generated later (40_ledger.sql)
-- from these documents; this step only writes headers, lines and loaded quantities.
--
-- Rules (see docs/LEGACY_MIGRATION.md for the why):
--   * Voided legacy documents are migrated voided and post nothing.
--   * Unposted (draft) Outlet Receives and supplier invoices that never touched stock are migrated
--     voided, with remarks saying so — they had no stock effect in legacy either.
--   * A receive line takes only what is still outstanding on its source line (chronologically);
--     anything beyond — legacy allowed over-receiving — becomes a RECONSTRUCTED Inventory Adjustment.
--   * One Norbiz receive has one source document, so a legacy receive spanning several sources is
--     split into one receive per source ("<ref>-2", "<ref>-3", ...).
--   * Line prices are the legacy net amount / quantity, so document totals match legacy.
--   * IDs: legacy IDs where the mapping is 1:1; new rows get explicit IDs above the legacy range.
-- ============================================================================================

-- Business date of a legacy document; impossible dates fall back to the creation date, then the cutover.
CREATE FUNCTION migration.doc_day(d timestamp, created timestamp) RETURNS timestamptz LANGUAGE sql STABLE AS $$
    SELECT coalesce(migration.day(d), migration.day(created), ((SELECT cutover FROM migration.ctx)::date)::timestamp AT TIME ZONE 'UTC')
$$;

CREATE FUNCTION migration.created(created timestamp, d timestamp) RETURNS timestamptz LANGUAGE sql STABLE AS $$
    SELECT coalesce(migration.at(created), migration.day(d), (SELECT cutover FROM migration.ctx))
$$;

CREATE FUNCTION migration.qty(q double precision) RETURNS numeric LANGUAGE sql IMMUTABLE AS $$
    SELECT round(coalesce(q, 0)::numeric, 4)
$$;

-- Effective unit price: net amount / quantity (legacy discounts folded in), else the list price.
CREATE FUNCTION migration.price(net numeric, q double precision, list numeric) RETURNS numeric LANGUAGE sql IMMUTABLE AS $$
    SELECT round(CASE WHEN coalesce(q, 0) <> 0 AND net IS NOT NULL THEN net / q::numeric ELSE coalesce(list, 0) END, 4)
$$;

CREATE FUNCTION migration.cid() RETURNS bigint LANGUAGE sql STABLE AS $$ SELECT company_id FROM migration.ctx $$;

-- Log impossible document dates.
INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'date', entity, id, 'Date ' || coalesce(d::text, 'NULL') || ' is impossible; used ' || migration.doc_day(d, c)::date
FROM (
    SELECT 'DeliveryReceipt' entity, id, date d, creationdate c FROM legacy.tbldeliveryreceipts
    UNION ALL SELECT 'OutletDeliveryReceipt', id, date, creationdate FROM legacy.tbloutletdeliveryreceipts
    UNION ALL SELECT 'OutletReceive', id, date, creationdate FROM legacy.tbloutletreceives
    UNION ALL SELECT 'StockTransfer', id, date, creationdate FROM legacy.tblstocktransfer
    UNION ALL SELECT 'InventoryAdjustment', id, date, creationdate FROM legacy.tblinventoryadjustment
    UNION ALL SELECT 'OutletInventoryAdjustment', id, date, creationdate FROM legacy.tbloutletinventoryadjustment
    UNION ALL SELECT 'OutletPullOut', id, date, creationdate FROM legacy.tblreturnslips
    UNION ALL SELECT 'PullOutReceive', id, date, creationdate FROM legacy.tbloutletpullout
    UNION ALL SELECT 'OutletDeliveryReturn', id, date, creationdate FROM legacy.tbloutletdeliveryreturns
    UNION ALL SELECT 'PurchaseInvoice', id, date, creationdate FROM legacy.tblsupplierinvoices
    UNION ALL SELECT 'Assembly', id, date, creationdate FROM legacy.tblassembly
) x WHERE migration.day(d) IS NULL;

-- ============================================================================================
-- Inventory adjustments — main (keep IDs) and outlet (headers +100,000; IA lines +3,000,000)
-- ============================================================================================

INSERT INTO inventory_adjustments (id, company_id, warehouse_id, reference_number, sheet_number, adjustment_date, reason,
                                   created_at, created_by, voided, voided_at, voided_by, loaded, origin)
SELECT a.id, migration.cid(), a.warehouseid, btrim(a.iteminvadjrefno), migration.trunc(a.iteminvadjno, 100),
       migration.doc_day(a.date, a.creationdate), migration.trunc(concat_ws(' — ', nullif(btrim(a.comments), ''), nullif(btrim(a.addremarks), '')), 255),
       migration.created(a.creationdate, a.date), migration.actor(a.createdbyid),
       a.void, CASE WHEN a.void THEN coalesce(migration.at(a.datevoid), migration.created(a.creationdate, a.date)) END,
       CASE WHEN a.void THEN migration.actor(a.voidedbyid) END, false, 'MIGRATED'
FROM legacy.tblinventoryadjustment a;

INSERT INTO inventory_adjustment_lines (id, adjustment_id, item_id, quantity, quantity_loaded, line_number)
SELECT d.id + 3000000, d.masterid, d.itemid,
       CASE WHEN d.adjustmenttype = 2 THEN -migration.qty(d.qty) ELSE migration.qty(d.qty) END, 0,
       row_number() OVER (PARTITION BY d.masterid ORDER BY d.id)
FROM legacy.tblinventoryadjustmentdetails d
WHERE EXISTS (SELECT 1 FROM inventory_adjustments a WHERE a.id = d.masterid);

INSERT INTO inventory_adjustments (id, company_id, warehouse_id, reference_number, sheet_number, adjustment_date, reason,
                                   created_at, created_by, voided, voided_at, voided_by, loaded, origin)
SELECT a.id + 100000, migration.cid(), a.warehouseid, btrim(a.iteminvadjrefno), migration.trunc(a.iteminvadjno, 100),
       migration.doc_day(a.date, a.creationdate), migration.trunc(a.comments, 255),
       migration.created(a.creationdate, a.date), migration.actor(a.createdbyid),
       a.void, CASE WHEN a.void THEN coalesce(migration.at(a.datevoid), migration.created(a.creationdate, a.date)) END,
       CASE WHEN a.void THEN migration.actor(a.voidedbyid) END, false, 'MIGRATED'
FROM legacy.tbloutletinventoryadjustment a;

INSERT INTO inventory_adjustment_lines (id, adjustment_id, item_id, quantity, quantity_loaded, line_number)
SELECT d.id, d.masterid + 100000, d.itemid,
       CASE WHEN d.adjustmenttype = 2 THEN -migration.qty(d.qty) ELSE migration.qty(d.qty) END, 0,
       row_number() OVER (PARTITION BY d.masterid ORDER BY d.id)
FROM legacy.tbloutletinventoryadjustmentdetails d
WHERE EXISTS (SELECT 1 FROM legacy.tbloutletinventoryadjustment a WHERE a.id = d.masterid);

-- ============================================================================================
-- Stock transfers and delivery receipts
-- ============================================================================================

-- The transfer each DR delivered (legacy stores the link on the transfer; it is 1:1).
CREATE TABLE migration.dr_transfer AS
SELECT s.drid AS dr_id, s.id AS transfer_id FROM legacy.tblstocktransfer s
JOIN legacy.tbldeliveryreceipts d ON d.id = s.drid
WHERE s.drid IS NOT NULL AND NOT s.void;
CREATE UNIQUE INDEX ON migration.dr_transfer (dr_id);

INSERT INTO stock_transfers (id, company_id, customer_id, warehouse_id, reference_number, sheet_number, transfer_date, remarks,
                             created_at, created_by, voided, voided_at, voided_by, loaded, origin)
SELECT s.id, migration.cid(), s.customerid, 1, btrim(s.refno), migration.trunc(s.sheetno, 100),
       migration.doc_day(s.date, s.creationdate), migration.trunc(s.comments, 255),
       migration.created(s.creationdate, s.date), migration.actor(s.createdbyid),
       s.void, CASE WHEN s.void THEN coalesce(migration.at(s.datevoided), migration.created(s.creationdate, s.date)) END,
       CASE WHEN s.void THEN migration.actor(s.voidedby) END,
       EXISTS (SELECT 1 FROM migration.dr_transfer t JOIN legacy.tbldeliveryreceipts d ON d.id = t.dr_id
               WHERE t.transfer_id = s.id AND NOT d.void),
       'MIGRATED'
FROM legacy.tblstocktransfer s;

INSERT INTO stock_transfer_lines (id, stock_transfer_id, item_id, quantity, unit_price, line_number, quantity_loaded)
SELECT d.id, d.masterid, d.itemid, migration.qty(d.quantity), round(coalesce(d.unitprice, 0), 4),
       row_number() OVER (PARTITION BY d.masterid ORDER BY d.id), 0
FROM legacy.tblstocktransferdetails d
WHERE EXISTS (SELECT 1 FROM stock_transfers s WHERE s.id = d.masterid);

UPDATE stock_transfer_lines l SET quantity_loaded = l.quantity
FROM stock_transfers s WHERE s.id = l.stock_transfer_id AND s.loaded;

INSERT INTO delivery_receipts (id, company_id, customer_id, warehouse_id, destination_warehouse_id, stock_transfer_id,
                               reference_number, sheet_number, delivery_date, remarks,
                               created_at, created_by, voided, voided_at, voided_by, loaded, origin)
SELECT d.id, migration.cid(), d.customerid, 1, c.warehouse_id,
       CASE WHEN NOT d.void THEN t.transfer_id END,
       btrim(d.drno), migration.trunc(d.sheetno, 100), migration.doc_day(d.date, d.creationdate), migration.trunc(d.comments, 255),
       migration.created(d.creationdate, d.date), migration.actor(d.createdbyid),
       d.void, CASE WHEN d.void THEN coalesce(migration.at(d.datevoided), migration.created(d.creationdate, d.date)) END,
       CASE WHEN d.void THEN migration.actor(d.voidedby) END, false, 'MIGRATED'
FROM legacy.tbldeliveryreceipts d
JOIN customers c ON c.id = d.customerid
LEFT JOIN migration.dr_transfer t ON t.dr_id = d.id;

INSERT INTO delivery_receipt_lines (id, delivery_receipt_id, item_id, quantity, unit_price, line_number, quantity_loaded)
SELECT d.id, d.drid, d.itemid, migration.qty(d.quantity), migration.price(d.netamount, d.quantity, d.sellingprice),
       row_number() OVER (PARTITION BY d.drid ORDER BY d.id), 0
FROM legacy.tbldeliveryreceiptdetails d
WHERE EXISTS (SELECT 1 FROM delivery_receipts r WHERE r.id = d.drid);

-- ============================================================================================
-- Generic receive allocation (Outlet Receive -> DR lines, Pull Out Receive -> pull out lines)
-- ============================================================================================

-- One row per legacy receive line: the source line it names, how much of it fits what is still
-- outstanding there (chronologically, live receives only), and the excess.
CREATE TABLE migration.receive_alloc (
    kind text NOT NULL,              -- 'OR' | 'OPO'
    receive_id bigint NOT NULL,      -- legacy receive header
    line_id bigint NOT NULL,         -- legacy receive line
    source_id bigint,                -- legacy source header (DR / return slip); NULL when unmatched
    source_line_id bigint,           -- legacy source line; NULL when unmatched
    item_id bigint NOT NULL,
    quantity numeric NOT NULL,       -- as entered in legacy
    allocated numeric NOT NULL,      -- part that loads the source line
    live boolean NOT NULL            -- false for voided / unposted receives (they load nothing)
);

-- Outlet receives: live = posted and not voided. A line whose source line is for another item is unmatched.
INSERT INTO migration.receive_alloc
WITH l AS (
    SELECT d.id, d.masterid, d.itemid, migration.qty(d.quantity) q, r.posted AND NOT r.void AS live,
           migration.doc_day(r.date, r.creationdate) AS day,
           CASE WHEN x.id IS NOT NULL THEN x.drid END AS src, CASE WHEN x.id IS NOT NULL THEN x.id END AS src_line,
           migration.qty(x.quantity) AS src_q
    FROM legacy.tbloutletreceivedetails d
    JOIN legacy.tbloutletreceives r ON r.id = d.masterid
    LEFT JOIN legacy.tbldeliveryreceiptdetails x ON x.id = d.ancdetailid AND x.drid = d.ancmasterid AND x.itemid = d.itemid),
c AS (SELECT l.*, sum(CASE WHEN live THEN q ELSE 0 END) OVER (PARTITION BY src_line ORDER BY day, masterid, id ROWS UNBOUNDED PRECEDING) AS cum FROM l)
SELECT 'OR', masterid, id, src, src_line, itemid, q,
       CASE WHEN src_line IS NULL THEN 0
            WHEN NOT live THEN q
            ELSE greatest(0, least(q, src_q - (cum - q))) END,
       live
FROM c;

-- Pull out receives: live = not voided. Source line by AncDetailId, else the slip's line for the same item.
INSERT INTO migration.receive_alloc
WITH l AS (
    SELECT d.id, d.masterid, d.itemid, migration.qty(d.quantity) q, NOT r.void AS live,
           migration.doc_day(r.date, r.creationdate) AS day,
           coalesce(x.returnslipmasterid, y.returnslipmasterid) AS src, coalesce(x.id, y.id) AS src_line,
           migration.qty(coalesce(x.quantityreturned, y.quantityreturned)) AS src_q
    FROM legacy.tbloutletpulloutdetail d
    JOIN legacy.tbloutletpullout r ON r.id = d.masterid
    LEFT JOIN legacy.tblreturnslipdetails x ON x.id = d.ancdetailid AND x.returnslipmasterid = d.ancmasterid AND x.itemid = d.itemid
    LEFT JOIN LATERAL (SELECT y.* FROM legacy.tblreturnslipdetails y
                       WHERE x.id IS NULL AND y.returnslipmasterid = d.ancmasterid AND y.itemid = d.itemid ORDER BY y.id LIMIT 1) y ON true),
c AS (SELECT l.*, sum(CASE WHEN live THEN q ELSE 0 END) OVER (PARTITION BY src_line ORDER BY day, masterid, id ROWS UNBOUNDED PRECEDING) AS cum FROM l)
SELECT 'OPO', masterid, id, src, src_line, itemid, q,
       CASE WHEN src_line IS NULL THEN 0
            WHEN NOT live THEN q
            ELSE greatest(0, least(q, src_q - (cum - q))) END,
       live
FROM c;

CREATE INDEX ON migration.receive_alloc (kind, receive_id);

-- One Norbiz receive per (legacy receive, source). The first source keeps the legacy ID and
-- reference; further ones get new IDs and "<ref>-2", "<ref>-3", ... Unmatched lines ride on the
-- primary part (as excess only).
CREATE TABLE migration.receive_part AS
SELECT kind, receive_id, source_id,
       row_number() OVER (PARTITION BY kind, receive_id ORDER BY source_id) AS part
FROM (SELECT DISTINCT kind, receive_id, source_id FROM migration.receive_alloc WHERE source_id IS NOT NULL) x;
ALTER TABLE migration.receive_part ADD COLUMN new_id bigint;

UPDATE migration.receive_part SET new_id = receive_id WHERE part = 1;
UPDATE migration.receive_part p SET new_id = n.new_id
FROM (SELECT kind, receive_id, source_id,
             (SELECT max(id) FROM legacy.tbloutletreceives) + row_number() OVER (ORDER BY receive_id, part) AS new_id
      FROM migration.receive_part WHERE kind = 'OR' AND part > 1) n
WHERE p.kind = n.kind AND p.receive_id = n.receive_id AND p.source_id = n.source_id;
UPDATE migration.receive_part p SET new_id = n.new_id
FROM (SELECT kind, receive_id, source_id,
             (SELECT max(id) FROM legacy.tbloutletpullout) + row_number() OVER (ORDER BY receive_id, part) AS new_id
      FROM migration.receive_part WHERE kind = 'OPO' AND part > 1) n
WHERE p.kind = n.kind AND p.receive_id = n.receive_id AND p.source_id = n.source_id;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'split', CASE kind WHEN 'OR' THEN 'OutletReceive' ELSE 'PullOutReceive' END, receive_id,
       'Received against ' || count(*) || ' source documents; split into one receive per source'
FROM migration.receive_part GROUP BY kind, receive_id HAVING count(*) > 1;

-- ---- outlet receives ----

INSERT INTO outlet_receives (id, company_id, delivery_receipt_id, customer_id, warehouse_id, reference_number, sheet_number,
                             receipt_date, remarks, created_at, created_by, voided, voided_at, voided_by, loaded, origin)
SELECT p.new_id, migration.cid(), p.source_id, dr.customer_id, dr.destination_warehouse_id,
       btrim(r.refno) || CASE WHEN p.part > 1 THEN '-' || p.part ELSE '' END,
       migration.trunc(r.sheetno, 100), migration.doc_day(r.date, r.creationdate),
       migration.trunc(concat_ws(' — ',
           CASE WHEN NOT r.posted AND NOT r.void THEN 'Unposted draft in legacy (no stock effect) — migrated as voided' END,
           CASE WHEN p.part > 1 THEN 'Split from legacy ' || btrim(r.refno) || ' (it received several delivery receipts)' END,
           nullif(btrim(r.comments), '')), 255),
       migration.created(r.creationdate, r.date), migration.actor(r.createdbyid),
       r.void OR NOT r.posted,
       CASE WHEN r.void OR NOT r.posted THEN coalesce(migration.at(r.datevoided), migration.created(r.creationdate, r.date)) END,
       CASE WHEN r.void THEN migration.actor(r.voidedby) WHEN NOT r.posted THEN 'legacy-migration' END,
       false, 'MIGRATED'
FROM migration.receive_part p
JOIN legacy.tbloutletreceives r ON r.id = p.receive_id
JOIN delivery_receipts dr ON dr.id = p.source_id
WHERE p.kind = 'OR' AND dr.destination_warehouse_id IS NOT NULL;

INSERT INTO outlet_receive_lines (id, outlet_receive_id, item_id, delivery_receipt_line_id, quantity, line_number, quantity_loaded)
SELECT a.line_id, p.new_id, a.item_id, a.source_line_id, a.allocated,
       row_number() OVER (PARTITION BY p.new_id ORDER BY a.line_id), 0
FROM migration.receive_alloc a
JOIN migration.receive_part p ON p.kind = a.kind AND p.receive_id = a.receive_id AND p.source_id = a.source_id
WHERE a.kind = 'OR' AND a.allocated > 0 AND EXISTS (SELECT 1 FROM outlet_receives o WHERE o.id = p.new_id);

UPDATE delivery_receipt_lines l SET quantity_loaded = least(l.quantity, s.q)
FROM (SELECT source_line_id, sum(allocated) q FROM migration.receive_alloc WHERE kind = 'OR' AND live GROUP BY 1) s
WHERE s.source_line_id = l.id;

UPDATE delivery_receipts r SET loaded = true
WHERE r.destination_warehouse_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM delivery_receipt_lines l WHERE l.delivery_receipt_id = r.id AND l.quantity_loaded < l.quantity);

-- ============================================================================================
-- Outlet delivery receipts (sales)
-- ============================================================================================

INSERT INTO outlet_delivery_receipts (id, company_id, customer_id, warehouse_id, agent_id, reference_number, sheet_number,
                                      delivery_date, remarks, created_at, created_by, voided, voided_at, voided_by, loaded, origin)
SELECT o.id, migration.cid(), o.customerid, c.warehouse_id, o.agentid, btrim(o.odrno), migration.trunc(o.sheetno, 100),
       migration.doc_day(o.date, o.creationdate), migration.trunc(o.comments, 255),
       migration.created(o.creationdate, o.date), migration.actor(o.createdbyid),
       o.void, CASE WHEN o.void THEN coalesce(migration.at(o.datevoided), migration.created(o.creationdate, o.date)) END,
       CASE WHEN o.void THEN migration.actor(o.voidedby) END, false, 'MIGRATED'
FROM legacy.tbloutletdeliveryreceipts o
JOIN customers c ON c.id = o.customerid;

INSERT INTO outlet_delivery_receipt_lines (id, outlet_delivery_receipt_id, item_id, quantity, unit_price, line_number, quantity_loaded)
SELECT d.id, d.odrid, d.itemid, migration.qty(d.quantity), migration.price(d.netamount, d.quantity, d.sellingprice),
       row_number() OVER (PARTITION BY d.odrid ORDER BY d.id), 0
FROM legacy.tbloutletdeliveryreceiptsdetails d
WHERE EXISTS (SELECT 1 FROM outlet_delivery_receipts o WHERE o.id = d.odrid);

-- Outlet delivery returns are matched to their source sales by match_returns.py (step 25).

-- ============================================================================================
-- Outlet pull outs (legacy return slips) and pull out receives
-- ============================================================================================

-- How legacy posted each live pull out: through main-warehouse transit (to be received later), or
-- straight into main-warehouse on-hand ("direct") — the latter gets a reconstructed receive below.
CREATE TABLE migration.pull_out_flow AS
SELECT s.id,
       CASE WHEN EXISTS (SELECT 1 FROM legacy.tbltransactiondetails t WHERE t.typeid = 10 AND t.masterid = s.id) THEN 'DIRECT'
            WHEN EXISTS (SELECT 1 FROM legacy.tbltransactiondetails t WHERE t.typeid = 20 AND t.masterid = s.id) THEN 'TRANSIT'
            ELSE 'NONE' END AS flow
FROM legacy.tblreturnslips s;

INSERT INTO outlet_pull_outs (id, company_id, customer_id, warehouse_id, destination_warehouse_id, pull_out_reason_id,
                              reference_number, sheet_number, pull_out_date, remarks,
                              created_at, created_by, voided, voided_at, voided_by, loaded, origin)
SELECT s.id, migration.cid(), s.customerid, c.warehouse_id, 1,
       CASE WHEN EXISTS (SELECT 1 FROM pull_out_reasons r WHERE r.id = s.pulloutreasonid) THEN s.pulloutreasonid END,
       btrim(s.crsno), migration.trunc(s.sheetno, 100), migration.doc_day(s.date, s.creationdate),
       migration.trunc(concat_ws(' — ', nullif(btrim(s.comments), ''), nullif(btrim(s.remark1), ''), nullif(btrim(s.remark2), '')), 255),
       migration.created(s.creationdate, s.date), migration.actor(s.createdbyid),
       s.void, CASE WHEN s.void THEN coalesce(migration.at(s.datevoid), migration.created(s.creationdate, s.date)) END,
       CASE WHEN s.void THEN migration.actor(s.voidedbyid) END, false, 'MIGRATED'
FROM legacy.tblreturnslips s
JOIN customers c ON c.id = s.customerid AND c.warehouse_id IS NOT NULL;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'skipped', 'OutletPullOut', s.id, 'Customer ' || s.customerid || ' has no stock of its own (not an outlet)' || CASE WHEN s.void THEN ' — voided in legacy' ELSE '' END
FROM legacy.tblreturnslips s WHERE NOT EXISTS (SELECT 1 FROM outlet_pull_outs p WHERE p.id = s.id);

INSERT INTO outlet_pull_out_lines (id, outlet_pull_out_id, item_id, quantity, unit_price, line_number, quantity_loaded)
SELECT d.id, d.returnslipmasterid, d.itemid, migration.qty(d.quantityreturned), migration.price(d.netamount, d.quantityreturned, d.soldprice),
       row_number() OVER (PARTITION BY d.returnslipmasterid ORDER BY d.id), 0
FROM legacy.tblreturnslipdetails d
WHERE EXISTS (SELECT 1 FROM outlet_pull_outs p WHERE p.id = d.returnslipmasterid);

INSERT INTO pull_out_receives (id, company_id, outlet_pull_out_id, customer_id, warehouse_id, reference_number, sheet_number,
                               receipt_date, remarks, created_at, created_by, voided, voided_at, voided_by, loaded, origin)
SELECT p.new_id, migration.cid(), p.source_id, po.customer_id, 1,
       btrim(r.refno) || CASE WHEN p.part > 1 THEN '-' || p.part ELSE '' END,
       migration.trunc(r.sheetno, 100), migration.doc_day(r.date, r.creationdate),
       migration.trunc(concat_ws(' — ',
           CASE WHEN p.part > 1 THEN 'Split from legacy ' || btrim(r.refno) || ' (it received several pull outs)' END,
           nullif(btrim(r.comments), '')), 255),
       migration.created(r.creationdate, r.date), migration.actor(r.createdbyid),
       r.void, CASE WHEN r.void THEN coalesce(migration.at(r.datevoided), migration.created(r.creationdate, r.date)) END,
       CASE WHEN r.void THEN migration.actor(r.voidedby) END, false, 'MIGRATED'
FROM migration.receive_part p
JOIN legacy.tbloutletpullout r ON r.id = p.receive_id
JOIN outlet_pull_outs po ON po.id = p.source_id
WHERE p.kind = 'OPO';

INSERT INTO pull_out_receive_lines (id, pull_out_receive_id, item_id, outlet_pull_out_line_id, quantity, line_number, quantity_loaded)
SELECT a.line_id, p.new_id, a.item_id, a.source_line_id, a.allocated,
       row_number() OVER (PARTITION BY p.new_id ORDER BY a.line_id), 0
FROM migration.receive_alloc a
JOIN migration.receive_part p ON p.kind = a.kind AND p.receive_id = a.receive_id AND p.source_id = a.source_id
WHERE a.kind = 'OPO' AND a.allocated > 0 AND EXISTS (SELECT 1 FROM pull_out_receives o WHERE o.id = p.new_id);

-- Reconstructed Pull Out Receive for every live pull out that legacy posted straight to main on-hand:
-- it receives the whole pull out on the same day.
CREATE TABLE migration.direct_pull_out AS
SELECT po.id AS pull_out_id,
       (SELECT coalesce(max(id), 0) FROM pull_out_receives) + row_number() OVER (ORDER BY po.id) AS receive_id,
       row_number() OVER (ORDER BY po.id) AS n
FROM outlet_pull_outs po JOIN migration.pull_out_flow f ON f.id = po.id
WHERE f.flow = 'DIRECT' AND NOT po.voided;

INSERT INTO pull_out_receives (id, company_id, outlet_pull_out_id, customer_id, warehouse_id, reference_number, sheet_number,
                               receipt_date, remarks, created_at, created_by, voided, voided_at, voided_by, loaded, origin)
SELECT d.receive_id, po.company_id, po.id, po.customer_id, 1, 'RCN-OPO-' || lpad(d.n::text, 6, '0'), po.sheet_number,
       po.pull_out_date,
       left('Reconstructed: legacy pull out ' || po.reference_number || ' went straight into main-warehouse on-hand (no transit step)', 255),
       po.created_at, (SELECT actor FROM migration.ctx), false, NULL, NULL, false, 'RECONSTRUCTED'
FROM migration.direct_pull_out d JOIN outlet_pull_outs po ON po.id = d.pull_out_id;

INSERT INTO pull_out_receive_lines (id, pull_out_receive_id, item_id, outlet_pull_out_line_id, quantity, line_number, quantity_loaded)
SELECT (SELECT coalesce(max(id), 0) FROM pull_out_receive_lines) + row_number() OVER (ORDER BY l.id),
       d.receive_id, l.item_id, l.id, l.quantity, l.line_number, 0
FROM migration.direct_pull_out d JOIN outlet_pull_out_lines l ON l.outlet_pull_out_id = d.pull_out_id;

UPDATE outlet_pull_out_lines l SET quantity_loaded = least(l.quantity, s.q)
FROM (SELECT r.outlet_pull_out_line_id, sum(r.quantity) q FROM pull_out_receive_lines r
      JOIN pull_out_receives h ON h.id = r.pull_out_receive_id WHERE NOT h.voided GROUP BY 1) s
WHERE s.outlet_pull_out_line_id = l.id;

UPDATE outlet_pull_outs p SET loaded = true
WHERE NOT EXISTS (SELECT 1 FROM outlet_pull_out_lines l WHERE l.outlet_pull_out_id = p.id AND l.quantity_loaded < l.quantity);

-- ============================================================================================
-- Purchases: supplier invoices -> Direct Purchase Invoices, item receives -> Purchase Receives
-- ============================================================================================

-- How each invoice posted in legacy: TRANSIT (to be received), ONHAND (straight to on-hand: gets a
-- reconstructed receive), or NONE (never touched stock).
CREATE TABLE migration.invoice_flow AS
SELECT h.id,
       CASE WHEN EXISTS (SELECT 1 FROM legacy.tbltransactiondetails t WHERE t.typeid = 15 AND t.masterid = h.id AND t.qtyin > 0) THEN 'ONHAND'
            WHEN EXISTS (SELECT 1 FROM legacy.tbltransactiondetails t WHERE t.typeid = 15 AND t.masterid = h.id) THEN 'TRANSIT'
            ELSE 'NONE' END AS flow,
       EXISTS (SELECT 1 FROM legacy.tblitemreceivedetails r JOIN legacy.tblitemreceive rh ON rh.id = r.masterid AND NOT rh.void
               WHERE r.poid = h.id) AS received
FROM legacy.tblsupplierinvoices h;

-- An invoice that never touched stock and was never received predates legacy inventory tracking:
-- it is migrated voided (a Direct invoice would otherwise post phantom transit).
INSERT INTO purchase_invoices (id, company_id, warehouse_id, supplier_id, purchase_order_id, reference_number, sheet_number,
                               invoice_date, remarks, discount_percentage, payment_status,
                               created_at, created_by, voided, voided_at, voided_by, loaded, origin)
SELECT h.id, migration.cid(), 1, h.supplierid, NULL,
       CASE WHEN row_number() OVER (PARTITION BY btrim(h.sivno) ORDER BY h.id) > 1 THEN btrim(h.sivno) || '-' || h.id ELSE btrim(h.sivno) END,
       migration.trunc(h.sheetno, 100), migration.doc_day(h.date, h.creationdate),
       migration.trunc(concat_ws(' — ',
           CASE WHEN NOT h.void AND f.flow = 'NONE' AND NOT f.received
                THEN 'Pre-inventory legacy invoice (no stock effect) — migrated as voided' END,
           nullif(btrim(h.comments), '')), 255),
       0,
       CASE WHEN coalesce(h.amountpaid, 0) >= coalesce(h.netamount, 0) AND coalesce(h.netamount, 0) > 0 THEN 'PAID'
            WHEN coalesce(h.amountpaid, 0) > 0 THEN 'PARTIALLY_PAID' ELSE 'UNPAID' END,
       migration.created(h.creationdate, h.date), migration.actor(h.createdbyid),
       h.void OR (f.flow = 'NONE' AND NOT f.received),
       CASE WHEN h.void OR (f.flow = 'NONE' AND NOT f.received)
            THEN coalesce(migration.at(h.datevoided), migration.created(h.creationdate, h.date)) END,
       CASE WHEN h.void THEN migration.actor(h.voidedby) WHEN f.flow = 'NONE' AND NOT f.received THEN 'legacy-migration' END,
       false, 'MIGRATED'
FROM legacy.tblsupplierinvoices h JOIN migration.invoice_flow f ON f.id = h.id
WHERE EXISTS (SELECT 1 FROM suppliers s WHERE s.id = h.supplierid);

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'renamed', 'PurchaseInvoice', p.id, 'Duplicate invoice number ' || btrim(h.sivno) || ' became ' || p.reference_number
FROM purchase_invoices p JOIN legacy.tblsupplierinvoices h ON h.id = p.id WHERE p.reference_number <> btrim(h.sivno);

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'voided', 'PurchaseInvoice', h.id, 'Never posted stock nor received in legacy (pre-inventory); migrated as voided'
FROM legacy.tblsupplierinvoices h JOIN migration.invoice_flow f ON f.id = h.id
WHERE NOT h.void AND f.flow = 'NONE' AND NOT f.received;

INSERT INTO purchase_invoice_lines (id, purchase_invoice_id, item_id, purchase_order_line_id, quantity, cost_price,
                                    discount_percentage, quantity_loaded, line_number)
SELECT d.id, d.supplierinvoiceid, d.itemid, NULL, migration.qty(d.quantity), migration.price(d.netamount, d.quantity, d.listprice),
       0, 0, row_number() OVER (PARTITION BY d.supplierinvoiceid ORDER BY d.id)
FROM legacy.tblsupplierinvoicedetails d
WHERE EXISTS (SELECT 1 FROM purchase_invoices p WHERE p.id = d.supplierinvoiceid);

INSERT INTO purchase_receives (id, company_id, warehouse_id, supplier_id, purchase_order_id, purchase_invoice_id, reference_number,
                               sheet_number, receipt_date, remarks, created_at, created_by, voided, voided_at, voided_by, loaded, origin)
SELECT r.id, migration.cid(), 1, pi.supplier_id, NULL, pi.id,
       CASE WHEN row_number() OVER (PARTITION BY btrim(r.recno) ORDER BY r.id) > 1 THEN btrim(r.recno) || '-' || r.id ELSE btrim(r.recno) END,
       migration.trunc(r.recrefno, 100), migration.doc_day(r.date, NULL),
       migration.trunc(concat_ws(' — ', nullif(btrim(r.comment), ''), nullif(btrim(r.remark1), ''), nullif(btrim(r.remark2), '')), 255),
       migration.created(NULL, r.date), migration.actor(coalesce(r.preparedbyid, r.receivedbyid)),
       r.void, CASE WHEN r.void THEN coalesce(migration.at(r.datevoided), migration.created(NULL, r.date)) END,
       CASE WHEN r.void THEN migration.actor(r.voidedbyid) END, false, 'MIGRATED'
FROM legacy.tblitemreceive r
JOIN LATERAL (SELECT poid FROM legacy.tblitemreceivedetails d WHERE d.masterid = r.id LIMIT 1) src ON true
JOIN purchase_invoices pi ON pi.id = src.poid;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'skipped', 'PurchaseReceive', r.id, 'No invoice to receive against'
FROM legacy.tblitemreceive r WHERE NOT EXISTS (SELECT 1 FROM purchase_receives p WHERE p.id = r.id);

INSERT INTO purchase_receive_lines (id, purchase_receive_id, item_id, purchase_order_line_id, purchase_invoice_line_id, quantity,
                                    quantity_loaded, line_number)
SELECT d.id, d.masterid, d.itemid, NULL,
       CASE WHEN EXISTS (SELECT 1 FROM purchase_invoice_lines l WHERE l.id = d.ancid) THEN d.ancid END,
       migration.qty(d.quantity), 0, row_number() OVER (PARTITION BY d.masterid ORDER BY d.id)
FROM legacy.tblitemreceivedetails d
WHERE EXISTS (SELECT 1 FROM purchase_receives p WHERE p.id = d.masterid);

-- Reconstructed Purchase Receive for invoices legacy posted straight to on-hand.
CREATE TABLE migration.onhand_invoice AS
SELECT p.id AS invoice_id,
       (SELECT coalesce(max(id), 0) FROM purchase_receives) + row_number() OVER (ORDER BY p.id) AS receive_id,
       row_number() OVER (ORDER BY p.id) AS n
FROM purchase_invoices p JOIN migration.invoice_flow f ON f.id = p.id
WHERE f.flow = 'ONHAND' AND NOT p.voided;

INSERT INTO purchase_receives (id, company_id, warehouse_id, supplier_id, purchase_order_id, purchase_invoice_id, reference_number,
                               sheet_number, receipt_date, remarks, created_at, created_by, voided, voided_at, voided_by, loaded, origin)
SELECT o.receive_id, p.company_id, 1, p.supplier_id, NULL, p.id, 'RCN-PR-' || lpad(o.n::text, 6, '0'), p.sheet_number, p.invoice_date,
       left('Reconstructed: legacy invoice ' || p.reference_number || ' posted straight into on-hand (no separate receive)', 255),
       p.created_at, (SELECT actor FROM migration.ctx), false, NULL, NULL, false, 'RECONSTRUCTED'
FROM migration.onhand_invoice o JOIN purchase_invoices p ON p.id = o.invoice_id;

INSERT INTO purchase_receive_lines (id, purchase_receive_id, item_id, purchase_order_line_id, purchase_invoice_line_id, quantity,
                                    quantity_loaded, line_number)
SELECT (SELECT coalesce(max(id), 0) FROM purchase_receive_lines) + row_number() OVER (ORDER BY l.id),
       o.receive_id, l.item_id, NULL, l.id, l.quantity, 0, l.line_number
FROM migration.onhand_invoice o JOIN purchase_invoice_lines l ON l.purchase_invoice_id = o.invoice_id;

UPDATE purchase_invoice_lines l SET quantity_loaded = least(l.quantity, s.q)
FROM (SELECT r.purchase_invoice_line_id, sum(r.quantity) q FROM purchase_receive_lines r
      JOIN purchase_receives h ON h.id = r.purchase_receive_id WHERE NOT h.voided AND r.purchase_invoice_line_id IS NOT NULL GROUP BY 1) s
WHERE s.purchase_invoice_line_id = l.id;

UPDATE purchase_invoices p SET loaded = true
WHERE NOT p.voided
  AND NOT EXISTS (SELECT 1 FROM purchase_invoice_lines l WHERE l.purchase_invoice_id = p.id AND l.quantity_loaded < l.quantity);

-- ============================================================================================
-- Assemblies (outputs keep their IDs; raw materials +1,000)
-- ============================================================================================

INSERT INTO assemblies (id, company_id, warehouse_id, reference_number, sheet_number, assembly_date, remarks,
                        created_at, created_by, voided, voided_at, voided_by, loaded, origin)
SELECT a.id, migration.cid(), 1, btrim(a.refno), migration.trunc(a.sheetno, 100), migration.doc_day(a.date, a.creationdate),
       migration.trunc(a.comments, 255), migration.created(a.creationdate, a.date), migration.actor(a.createdbyid),
       a.void, CASE WHEN a.void THEN coalesce(migration.at(a.datevoided), migration.created(a.creationdate, a.date)) END,
       CASE WHEN a.void THEN migration.actor(a.voidedby) END, false, 'MIGRATED'
FROM legacy.tblassembly a;

INSERT INTO assembly_lines (id, assembly_id, item_id, kind, bill_of_material_id, quantity, line_number, quantity_loaded)
SELECT d.id, d.masterid, d.itemid, 'OUTPUT',
       CASE WHEN EXISTS (SELECT 1 FROM bills_of_materials b WHERE b.id = d.bomid) THEN d.bomid END,
       migration.qty(d.quantity), row_number() OVER (PARTITION BY d.masterid ORDER BY d.id), 0
FROM legacy.tblassemblydetail d WHERE EXISTS (SELECT 1 FROM assemblies a WHERE a.id = d.masterid);

INSERT INTO assembly_lines (id, assembly_id, item_id, kind, bill_of_material_id, quantity, line_number, quantity_loaded)
SELECT d.id + 1000, d.masterid, d.itemid, 'MATERIAL', NULL, migration.qty(d.quantity),
       (SELECT count(*) FROM legacy.tblassemblydetail o WHERE o.masterid = d.masterid) + row_number() OVER (PARTITION BY d.masterid ORDER BY d.id), 0
FROM legacy.tblassemblyrawmaterial d WHERE EXISTS (SELECT 1 FROM assemblies a WHERE a.id = d.masterid);

-- ============================================================================================
-- Reconstructed Inventory Adjustments for receive excess (legacy over-receiving)
-- ============================================================================================

-- One adjustment per legacy receive that took in more than its source still had outstanding:
-- the excess lands as an on-hand increase in the receiving warehouse, on the receive's date.
CREATE TABLE migration.receive_excess AS
SELECT a.kind, a.receive_id, a.item_id, sum(a.quantity - a.allocated) AS quantity
FROM migration.receive_alloc a
WHERE a.live AND a.quantity > a.allocated
GROUP BY a.kind, a.receive_id, a.item_id;

CREATE TABLE migration.excess_adjustment AS
SELECT kind, receive_id,
       (SELECT max(id) FROM inventory_adjustments) + row_number() OVER (ORDER BY kind, receive_id) AS adjustment_id,
       row_number() OVER (ORDER BY kind, receive_id) AS n
FROM (SELECT DISTINCT kind, receive_id FROM migration.receive_excess) x;

INSERT INTO inventory_adjustments (id, company_id, warehouse_id, reference_number, sheet_number, adjustment_date, reason,
                                   created_at, created_by, voided, voided_at, voided_by, loaded, origin)
SELECT e.adjustment_id, migration.cid(),
       CASE e.kind WHEN 'OR' THEN c.warehouse_id ELSE 1 END,
       'RCN-IA-' || lpad(e.n::text, 6, '0'), migration.trunc(h.sheetno, 100), migration.doc_day(h.date, h.creationdate),
       left('Reconstructed: legacy ' || CASE e.kind WHEN 'OR' THEN 'outlet receive ' ELSE 'pull out receive ' END || btrim(h.refno)
            || ' took in more than its source had outstanding', 255),
       migration.created(h.creationdate, h.date), (SELECT actor FROM migration.ctx), false, NULL, NULL, false, 'RECONSTRUCTED'
FROM migration.excess_adjustment e
JOIN LATERAL (SELECT refno, sheetno, date, creationdate, customerid FROM legacy.tbloutletreceives WHERE e.kind = 'OR' AND id = e.receive_id
              UNION ALL SELECT refno, sheetno, date, creationdate, customerid FROM legacy.tbloutletpullout WHERE e.kind = 'OPO' AND id = e.receive_id) h ON true
LEFT JOIN customers c ON c.id = h.customerid;

INSERT INTO inventory_adjustment_lines (id, adjustment_id, item_id, quantity, quantity_loaded, line_number)
SELECT (SELECT max(id) FROM inventory_adjustment_lines) + row_number() OVER (ORDER BY e.adjustment_id, x.item_id),
       e.adjustment_id, x.item_id, x.quantity, 0, row_number() OVER (PARTITION BY e.adjustment_id ORDER BY x.item_id)
FROM migration.excess_adjustment e JOIN migration.receive_excess x ON x.kind = e.kind AND x.receive_id = e.receive_id;

-- ============================================================================================
-- Receives that could not be migrated (logged for the report)
-- ============================================================================================

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'skipped', 'OutletReceive', r.id,
       CASE WHEN NOT EXISTS (SELECT 1 FROM legacy.tbloutletreceivedetails d WHERE d.masterid = r.id) THEN 'No lines'
            ELSE 'No line matches an item on its delivery receipt (any quantity became a reconstructed adjustment)' END
       || CASE WHEN r.void THEN ' — voided in legacy' WHEN NOT r.posted THEN ' — unposted in legacy' ELSE '' END
FROM legacy.tbloutletreceives r WHERE NOT EXISTS (SELECT 1 FROM migration.receive_part p WHERE p.kind = 'OR' AND p.receive_id = r.id);

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'skipped', 'PullOutReceive', r.id,
       CASE WHEN NOT EXISTS (SELECT 1 FROM legacy.tbloutletpulloutdetail d WHERE d.masterid = r.id) THEN 'No lines'
            ELSE 'No line matches an item on its pull out (any quantity became a reconstructed adjustment)' END
       || CASE WHEN r.void THEN ' — voided in legacy' ELSE '' END
FROM legacy.tbloutletpullout r WHERE NOT EXISTS (SELECT 1 FROM migration.receive_part p WHERE p.kind = 'OPO' AND p.receive_id = r.id);
