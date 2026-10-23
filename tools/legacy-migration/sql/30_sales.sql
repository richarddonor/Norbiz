-- ============================================================================================
-- 30 — Direct sales and the stock documents that come with them:
--   tblSalesInvoices          -> Sales Invoice (Direct, main warehouse)
--   tblSIReturnSlips          -> Sales Invoice Return (stock back in; its credit is the "Sales Return"
--                                credit memo legacy raised, see 35_accounting.sql)
--   tblItemEntries            -> Inventory Adjustment (increase)
--   tblItemDisbursements      -> Inventory Adjustment (decrease)
-- A legacy database without these (jbsKarutora never used them) loads nothing here.
--
-- Rules (docs/LEGACY_MIGRATION.md "Sales"):
--   * Line unit price = legacy line net / quantity, scaled by header net / sum of line nets, so the
--     invoice-level discount legacy kept on the header is folded in and the lines add up to the
--     legacy net amount (Norbiz keeps no header discount on migrated invoices).
--   * Stock: a line posts a movement only when legacy posted it (the ledger, tblTransactionDetails
--     type 11 / 14 / 12 / 13, has that document and item). Non-inventory lines never did.
--   * An empty, zero-value, never-posted invoice was a legacy draft: migrated voided.
--   * The invoice type (FOC, Shopee, ...), customer branch, PO number and agent have no Norbiz
--     field: they go into the remarks.
-- ============================================================================================

-- Business date with a better fallback for the long legacy sequences (SI, SIR): an impossible date
-- (legacy has years 1819 and 4208) takes the latest valid date of the documents numbered before it.
CREATE FUNCTION migration.seq_day(d timestamp, created timestamp, prev timestamptz) RETURNS timestamptz LANGUAGE sql STABLE AS $$
    SELECT coalesce(migration.day(d), migration.day(created), prev, ((SELECT cutover FROM migration.ctx)::date)::timestamp AT TIME ZONE 'UTC')
$$;

-- ============================================================================================
-- Sales invoices
-- ============================================================================================

CREATE TABLE migration.si AS
SELECT s.*,
       migration.seq_day(s.date, s.creationdate, max(migration.day(s.date)) OVER (ORDER BY s.id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING)) AS day,
       EXISTS (SELECT 1 FROM legacy.tbltransactiondetails t WHERE t.typeid = 11 AND t.masterid = s.id) AS posted_stock,
       (SELECT sum(d.netamount) FROM legacy.tblsalesinvoicedetails d WHERE d.salesinvoiceid = s.id) AS lines_net,
       EXISTS (SELECT 1 FROM legacy.tblsalesinvoicedetails d WHERE d.salesinvoiceid = s.id) AS has_lines
FROM legacy.tblsalesinvoices s
WHERE EXISTS (SELECT 1 FROM customers c WHERE c.id = s.customerid);
CREATE UNIQUE INDEX ON migration.si (id);

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'skipped', 'SalesInvoice', s.id, 'Customer ' || coalesce(migration.legacy_id(s.customerid)::text, 'NULL') || ' does not exist'
FROM legacy.tblsalesinvoices s WHERE NOT EXISTS (SELECT 1 FROM migration.si m WHERE m.id = s.id);

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'date', 'SalesInvoice', id, 'Date ' || coalesce(date::text, 'NULL') || ' is impossible; used ' || day::date
FROM migration.si WHERE migration.day(date) IS NULL;

-- Empty legacy drafts: no lines, no value, never posted.
CREATE TABLE migration.si_draft AS
SELECT id FROM migration.si WHERE NOT void AND NOT has_lines AND coalesce(netamount, 0) <= 0 AND NOT posted_stock;

INSERT INTO sales_invoices (id, company_id, customer_id, warehouse_id, delivery_receipt_id, reference_number, sheet_number,
                            invoice_date, remarks, discount_percentage, net_amount, term_days, due_date, payment_status,
                            created_at, created_by, voided, voided_at, voided_by, origin)
SELECT s.id, migration.cid(), s.customerid, migration.main_wh(), NULL,
       CASE WHEN row_number() OVER (PARTITION BY btrim(s.sino) ORDER BY s.id) > 1 OR migration.trunc(s.sino, 50) IS NULL
            THEN left(coalesce(btrim(s.sino), 'SI'), 38) || '-' || migration.legacy_id(s.id) ELSE btrim(s.sino) END,
       migration.trunc(s.sheetno, 100), s.day,
       migration.trunc(concat_ws(' — ',
           CASE WHEN s.id IN (SELECT id FROM migration.si_draft) THEN 'Empty unposted draft in legacy — migrated as voided' END,
           nullif(btrim(tt.name), ''),
           CASE WHEN b.branch IS NOT NULL THEN 'Branch: ' || btrim(b.branch) END,
           CASE WHEN nullif(btrim(s.customerpono), '') IS NOT NULL THEN 'PO ' || btrim(s.customerpono) END,
           CASE WHEN e.id IS NOT NULL THEN 'Agent: ' || coalesce(nullif(btrim(concat_ws(' ', e.firstname, e.lastname)), ''), btrim(e.employeecode)) END,
           nullif(btrim(s.comment), '')), 255),
       0, round(greatest(coalesce(s.netamount, 0), 0), 2), migration.term_days(s.termsid),
       s.day + make_interval(days => migration.term_days(s.termsid)),
       'UNPAID',
       migration.created(s.creationdate, s.date), migration.actor(coalesce(s.createdbyid, s.encodedby)),
       s.void OR s.id IN (SELECT id FROM migration.si_draft),
       CASE WHEN s.void OR s.id IN (SELECT id FROM migration.si_draft)
            THEN coalesce(migration.at(s.datevoided), migration.created(s.creationdate, s.date)) END,
       CASE WHEN s.void THEN migration.actor(s.voidedby) WHEN s.id IN (SELECT id FROM migration.si_draft) THEN 'legacy-migration' END,
       'MIGRATED'
FROM migration.si s
LEFT JOIN legacy.tblsitransactiontypes tt ON tt.id = s.sitranstypeid
LEFT JOIN legacy.tblcustomerbranches b ON b.id = s.customerbranchid
LEFT JOIN legacy.tblemployees e ON e.id = s.agentid;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'voided', 'SalesInvoice', id, 'Empty unposted draft (no lines, no value) — migrated as voided' FROM migration.si_draft
UNION ALL
SELECT 'renamed', 'SalesInvoice', p.id, 'Duplicate/blank invoice number ' || coalesce(btrim(s.sino), 'NULL') || ' became ' || p.reference_number
FROM sales_invoices p JOIN migration.si s ON s.id = p.id WHERE p.reference_number IS DISTINCT FROM btrim(s.sino)
UNION ALL
SELECT 'zero', 'SalesInvoice', id, 'Zero-value invoice (' || coalesce((SELECT name FROM legacy.tblsitransactiontypes t WHERE t.id = s.sitranstypeid), 'no type')
       || '): migrated without a receivable'
FROM migration.si s WHERE NOT void AND coalesce(netamount, 0) <= 0 AND id NOT IN (SELECT id FROM migration.si_draft)
UNION ALL
SELECT 'unposted', 'SalesInvoice', id, 'Legacy never posted its stock: migrated without stock movements'
FROM migration.si s WHERE NOT void AND has_lines AND NOT posted_stock;

ANALYZE sales_invoices;

-- Lines: legacy net per line scaled to the header net (folds the header discount in).
INSERT INTO sales_invoice_lines (id, sales_invoice_id, item_id, delivery_receipt_line_id, quantity, unit_price, discount_percentage, line_number)
SELECT d.id, d.salesinvoiceid, d.itemid, NULL, migration.qty(d.quantity),
       CASE WHEN coalesce(s.lines_net, 0) <> 0 AND coalesce(d.quantity, 0) <> 0 AND d.netamount IS NOT NULL
            THEN round(d.netamount * (coalesce(s.netamount, 0) / s.lines_net) / d.quantity::numeric, 4)
            ELSE migration.price(d.netamount, d.quantity, d.sellingprice) END,
       0, row_number() OVER (PARTITION BY d.salesinvoiceid ORDER BY d.id)
FROM legacy.tblsalesinvoicedetails d
JOIN migration.si s ON s.id = d.salesinvoiceid
WHERE EXISTS (SELECT 1 FROM items i WHERE i.id = d.itemid);

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'skipped', 'SalesInvoiceLine', d.id, 'Item ' || migration.legacy_id(d.itemid) || ' does not exist'
FROM legacy.tblsalesinvoicedetails d JOIN migration.si s ON s.id = d.salesinvoiceid
WHERE NOT EXISTS (SELECT 1 FROM items i WHERE i.id = d.itemid);

-- Which (invoice, item) pairs legacy posted to stock.
CREATE TABLE migration.si_posted AS
SELECT DISTINCT masterid AS invoice_id, itemid AS item_id FROM legacy.tbltransactiondetails WHERE typeid = 11;
CREATE UNIQUE INDEX ON migration.si_posted (invoice_id, item_id);

-- ============================================================================================
-- Sales invoice returns -> Sales Invoice Returns (keep legacy IDs)
-- Each legacy return line names the invoice line it returns (SIDetailID). The credit is the "Sales
-- Return" credit memo legacy raised for the slip (tblCustomerCreditMemos.UICustomerReturnId): it is
-- not migrated as a memo but becomes the return's own credit, with the memo's applications
-- (35_accounting.sql). A slip legacy never credited gets no credit.
-- Stock: a line moves stock only when legacy posted it (ledger type 14 has that slip and item).
-- Legacy let a line be returned more than once; the invoice line's quantity_loaded is capped at
-- its quantity and the excess is logged (over-returned).
-- ============================================================================================

CREATE TABLE migration.sir AS
SELECT r.*,
       migration.seq_day(r.date, r.creationdate, max(migration.day(r.date)) OVER (ORDER BY r.id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING)) AS day,
       (SELECT c.id FROM legacy.tblcustomercreditmemos c WHERE c.uicustomerreturnid = r.id AND NOT c.void ORDER BY c.id LIMIT 1) AS memo_id
FROM legacy.tblsireturnslips r
WHERE EXISTS (SELECT 1 FROM customers c WHERE c.id = r.customerid);
CREATE UNIQUE INDEX ON migration.sir (id);

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'skipped', 'SalesInvoiceReturn', r.id, 'Customer ' || coalesce(migration.legacy_id(r.customerid)::text, 'NULL') || ' does not exist'
FROM legacy.tblsireturnslips r WHERE NOT EXISTS (SELECT 1 FROM migration.sir m WHERE m.id = r.id);

INSERT INTO sales_invoice_returns (id, company_id, customer_id, warehouse_id, reference_number, sheet_number, return_date, remarks,
                                   net_amount, created_at, created_by, voided, voided_at, voided_by, origin)
SELECT r.id, migration.cid(), r.customerid, migration.main_wh(),
       CASE WHEN row_number() OVER (PARTITION BY btrim(r.sirno) ORDER BY r.id) > 1 OR migration.trunc(r.sirno, 50) IS NULL
            THEN left(coalesce(btrim(r.sirno), 'SIR'), 38) || '-' || migration.legacy_id(r.id) ELSE btrim(r.sirno) END,
       migration.trunc(r.sheetno, 100), r.day,
       migration.trunc(concat_ws(' — ',
           CASE WHEN m.id IS NOT NULL THEN 'Credited by legacy ' || coalesce(nullif(btrim(m.cmno), ''), 'credit memo ' || migration.legacy_id(m.id))
                WHEN NOT r.void THEN 'Legacy raised no credit memo (no credit)' END,
           nullif(btrim(r.comments), '')), 255),
       round(greatest(coalesce(m.netamount, r.netamount, 0), 0), 2),
       migration.created(r.creationdate, r.date), migration.actor(coalesce(r.createdbyid, r.preparedby)),
       r.void, CASE WHEN r.void THEN coalesce(migration.at(r.datevoid), migration.created(r.creationdate, r.date)) END,
       CASE WHEN r.void THEN migration.actor(r.voidedby) END,
       'MIGRATED'
FROM migration.sir r
LEFT JOIN legacy.tblcustomercreditmemos m ON m.id = r.memo_id;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'date', 'SalesInvoiceReturn', id, 'Date ' || coalesce(date::text, 'NULL') || ' is impossible; used ' || day::date
FROM migration.sir WHERE migration.day(date) IS NULL
UNION ALL
SELECT 'no-credit', 'SalesInvoiceReturn', id, 'Legacy raised no credit memo for it: migrated without a credit'
FROM migration.sir WHERE NOT void AND memo_id IS NULL AND coalesce(netamount, 0) > 0
UNION ALL
SELECT 'renamed', 'SalesInvoiceReturn', p.id, 'Duplicate/blank return number ' || coalesce(btrim(r.sirno), 'NULL') || ' became ' || p.reference_number
FROM sales_invoice_returns p JOIN migration.sir r ON r.id = p.id WHERE p.reference_number IS DISTINCT FROM btrim(r.sirno);

-- Which (return, item) pairs legacy posted to stock.
CREATE TABLE migration.sir_posted AS
SELECT DISTINCT masterid AS return_id, itemid AS item_id FROM legacy.tbltransactiondetails WHERE typeid = 14;
CREATE UNIQUE INDEX ON migration.sir_posted (return_id, item_id);

INSERT INTO sales_invoice_return_lines (id, sales_invoice_return_id, item_id, sales_invoice_line_id, quantity, unit_price, line_number, posts_stock)
SELECT d.id, d.returnslipmasterid, d.itemid,
       (SELECT l.id FROM sales_invoice_lines l WHERE l.id = d.sidetailid AND l.item_id = d.itemid),
       migration.qty(d.quantityreturned),
       migration.price(d.netamount, d.quantityreturned, d.soldprice),
       row_number() OVER (PARTITION BY d.returnslipmasterid ORDER BY d.id),
       EXISTS (SELECT 1 FROM migration.sir_posted p WHERE p.return_id = d.returnslipmasterid AND p.item_id = d.itemid)
FROM legacy.tblsireturnslipdetails d
WHERE EXISTS (SELECT 1 FROM migration.sir r WHERE r.id = d.returnslipmasterid)
  AND EXISTS (SELECT 1 FROM items i WHERE i.id = d.itemid)
  AND coalesce(d.quantityreturned, 0) <> 0;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'skipped', 'SalesInvoiceReturnLine', d.id, 'Item ' || migration.legacy_id(d.itemid) || ' does not exist'
FROM legacy.tblsireturnslipdetails d
WHERE EXISTS (SELECT 1 FROM migration.sir r WHERE r.id = d.returnslipmasterid) AND NOT EXISTS (SELECT 1 FROM items i WHERE i.id = d.itemid)
UNION ALL
SELECT 'unlinked', 'SalesInvoiceReturnLine', l.id, 'Names no migrated invoice line (legacy SI detail '
       || coalesce(migration.legacy_id(d.sidetailid)::text, 'none') || '): kept without one'
FROM sales_invoice_return_lines l JOIN legacy.tblsireturnslipdetails d ON d.id = l.id
JOIN sales_invoice_returns h ON h.id = l.sales_invoice_return_id
WHERE h.company_id = migration.cid() AND l.sales_invoice_line_id IS NULL
UNION ALL
SELECT 'unposted', 'SalesInvoiceReturnLine', l.id, 'Legacy never posted it to stock: migrated without a stock movement'
FROM sales_invoice_return_lines l JOIN sales_invoice_returns h ON h.id = l.sales_invoice_return_id
WHERE h.company_id = migration.cid() AND NOT h.voided AND NOT l.posts_stock
UNION ALL
SELECT 'before-invoice', 'SalesInvoiceReturn', h.id, 'Dated ' || h.return_date::date || ', before the invoice it returns ('
       || min(s.reference_number) || ', ' || min(s.invoice_date)::date || '): legacy date kept'
FROM sales_invoice_returns h JOIN sales_invoice_return_lines l ON l.sales_invoice_return_id = h.id
JOIN sales_invoice_lines sl ON sl.id = l.sales_invoice_line_id JOIN sales_invoices s ON s.id = sl.sales_invoice_id
WHERE h.company_id = migration.cid() AND NOT h.voided AND h.return_date < s.invoice_date
GROUP BY h.id, h.return_date;

-- Returned quantities on the invoice lines (live returns only), capped at what was sold.
CREATE TABLE migration.si_returned AS
SELECT l.sales_invoice_line_id AS line_id, sum(l.quantity) AS returned
FROM sales_invoice_return_lines l JOIN sales_invoice_returns h ON h.id = l.sales_invoice_return_id
WHERE h.company_id = migration.cid() AND NOT h.voided AND l.sales_invoice_line_id IS NOT NULL
GROUP BY 1;

UPDATE sales_invoice_lines l SET quantity_loaded = least(l.quantity, x.returned)
FROM migration.si_returned x WHERE x.line_id = l.id;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'over-returned', 'SalesInvoiceLine', l.id, 'Sold ' || trim_scale(l.quantity) || ', returned ' || trim_scale(x.returned)
       || ' in legacy: counted as fully returned'
FROM sales_invoice_lines l JOIN migration.si_returned x ON x.line_id = l.id WHERE x.returned > l.quantity;

UPDATE sales_invoices s SET loaded = true
WHERE s.company_id = migration.cid() AND s.origin = 'MIGRATED'
  AND EXISTS (SELECT 1 FROM sales_invoice_lines l WHERE l.sales_invoice_id = s.id)
  AND NOT EXISTS (SELECT 1 FROM sales_invoice_lines l WHERE l.sales_invoice_id = s.id AND l.quantity_loaded < l.quantity);

-- ============================================================================================
-- Item entries (+) and item disbursements (-) -> Inventory Adjustments
-- Headers +300,000 / +400,000; lines +5,000,000 / +6,000,000.
-- ============================================================================================

INSERT INTO inventory_adjustments (id, company_id, warehouse_id, reference_number, sheet_number, adjustment_date, reason,
                                   created_at, created_by, voided, voided_at, voided_by, loaded, origin)
SELECT e.id + 300000, migration.cid(), coalesce((SELECT w.id FROM warehouses w WHERE w.id = e.warehouseid), migration.main_wh()),
       CASE WHEN row_number() OVER (PARTITION BY upper(btrim(e.itemno)) ORDER BY e.id) > 1 OR migration.trunc(e.itemno, 50) IS NULL
            THEN 'IE-' || lpad(migration.legacy_id(e.id)::text, 6, '0') ELSE upper(btrim(e.itemno)) END,
       migration.trunc(e.itemrefno, 100), migration.doc_day(e.date, e.creationdate),
       migration.trunc(concat_ws(' — ', 'Item entry (legacy)', nullif(btrim(e.comments), '')), 255),
       migration.created(e.creationdate, e.date), migration.actor(coalesce(e.createdbyid, e.preparedbyid)),
       e.void OR NOT EXISTS (SELECT 1 FROM legacy.tbltransactiondetails t WHERE t.typeid = 12 AND t.masterid = e.id),
       CASE WHEN e.void OR NOT EXISTS (SELECT 1 FROM legacy.tbltransactiondetails t WHERE t.typeid = 12 AND t.masterid = e.id)
            THEN coalesce(migration.at(e.datevoid), migration.created(e.creationdate, e.date)) END,
       CASE WHEN e.void THEN migration.actor(e.voidedbyid)
            WHEN NOT EXISTS (SELECT 1 FROM legacy.tbltransactiondetails t WHERE t.typeid = 12 AND t.masterid = e.id) THEN 'legacy-migration' END,
       false, 'MIGRATED'
FROM legacy.tblitementries e;

INSERT INTO inventory_adjustment_lines (id, adjustment_id, item_id, quantity, quantity_loaded, line_number)
SELECT d.id + 5000000, d.masterid + 300000, d.itemid, migration.qty(d.qty), 0, row_number() OVER (PARTITION BY d.masterid ORDER BY d.id)
FROM legacy.tblitementrydetails d
WHERE EXISTS (SELECT 1 FROM legacy.tblitementries e WHERE e.id = d.masterid)
  AND EXISTS (SELECT 1 FROM items i WHERE i.id = d.itemid) AND coalesce(d.qty, 0) <> 0;

INSERT INTO inventory_adjustments (id, company_id, warehouse_id, reference_number, sheet_number, adjustment_date, reason,
                                   created_at, created_by, voided, voided_at, voided_by, loaded, origin)
SELECT x.id + 400000, migration.cid(), coalesce((SELECT w.id FROM warehouses w WHERE w.id = x.warehouseid), migration.main_wh()),
       CASE WHEN row_number() OVER (PARTITION BY upper(btrim(x.refno)) ORDER BY x.id) > 1 OR migration.trunc(x.refno, 50) IS NULL
            THEN 'ITMD-' || lpad(migration.legacy_id(x.id)::text, 6, '0') ELSE upper(btrim(x.refno)) END,
       migration.trunc(x.sheetno, 100), migration.doc_day(x.date, NULL),
       migration.trunc(concat_ws(' — ', 'Item disbursement (legacy)', nullif(btrim(x.comment), '')), 255),
       migration.created(NULL, x.date), migration.actor(x.encodedby),
       x.void OR NOT EXISTS (SELECT 1 FROM legacy.tbltransactiondetails t WHERE t.typeid = 13 AND t.masterid = x.id),
       CASE WHEN x.void OR NOT EXISTS (SELECT 1 FROM legacy.tbltransactiondetails t WHERE t.typeid = 13 AND t.masterid = x.id)
            THEN coalesce(migration.at(x.datevoided), migration.created(NULL, x.date)) END,
       CASE WHEN x.void THEN migration.actor(x.voidedbyid)
            WHEN NOT EXISTS (SELECT 1 FROM legacy.tbltransactiondetails t WHERE t.typeid = 13 AND t.masterid = x.id) THEN 'legacy-migration' END,
       false, 'MIGRATED'
FROM legacy.tblitemdisbursements x;

INSERT INTO inventory_adjustment_lines (id, adjustment_id, item_id, quantity, quantity_loaded, line_number)
SELECT d.id + 6000000, d.masterid + 400000, d.itemid, -migration.qty(d.quantity), 0, row_number() OVER (PARTITION BY d.masterid ORDER BY d.id)
FROM legacy.tblitemdisbursementdetails d
WHERE EXISTS (SELECT 1 FROM legacy.tblitemdisbursements x WHERE x.id = d.masterid)
  AND EXISTS (SELECT 1 FROM items i WHERE i.id = d.itemid) AND coalesce(d.quantity, 0) <> 0;

ANALYZE sales_invoices; ANALYZE sales_invoice_lines; ANALYZE sales_invoice_returns; ANALYZE sales_invoice_return_lines;
ANALYZE inventory_adjustments; ANALYZE inventory_adjustment_lines;
