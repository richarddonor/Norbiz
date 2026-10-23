-- ============================================================================================
-- 35 — Receivables and payables (docs/ACCOUNTING.md), rebuilt from the legacy subledger:
--   tblCustomerPayments + Details (tenders) + Links  -> Customer Payments, tenders, allocations
--   tblCustomerCreditMemos (+ its carrier payment)    -> Customer CREDIT memos (CCM), applied inline; a
--                                                       return slip's "Sales Return" memo is instead the
--                                                       credit of its Sales Invoice Return (30_sales.sql)
--   tblCustomerDebitMemos (hand-made ones)            -> Customer DEBIT memos (CDM)
--   tblCustomerRefunds                                -> Customer Refunds
--   tblDeposits + Details                             -> Deposits (cash and checks)
--   tblSupplierPayments + Details + Links             -> Supplier Payments, tenders, allocations
--   Sales Invoices (30_sales.sql), tracked Purchase Invoices, the above  -> open_items
-- and the bank book (bank_entries) those post. Written as OpenItemService / the document services
-- would leave them: the open item amounts are the sums of their active allocations.
--
-- How legacy keeps it: every payment detail is a tender (mode 1 Cash, 2 Check, 3 Bank Transfer,
-- 4 Others, 5 Credit Card); a credit memo is posted as a "payment" whose only detail has mode 101
-- (Credit Memo), so its links are the memo's applications. Links (PayableTypeID 1 = Sales Invoice,
-- 2 = Debit Memo, 102 = Refund) apply a tender to a charge. legacy amountPaid + PDC = the links of
-- the tenders that did not bounce; PDC = those by checks not yet cleared.
--
-- Rules (docs/LEGACY_MIGRATION.md "Accounting"):
--   * Voided payments / tenders / links post nothing. A bounced check's applications are made and
--     reversed (BOUNCED) on its bounce date — legacy has no bounce date, so the check's last deposit
--     date (else its check date, else the payment date) stands in.
--   * Check status: bounced -> BOUNCED; legacy Cleared -> CLEARED; deposited -> DEPOSITED; else ON_HAND.
--     Cash in a deposit is CLEARED (as DepositService does). Transfers / cards / others are CLEARED.
--   * Bank transfers and cards land in the account of the legacy deposit that carried them, on its
--     date; legacy deposits keep only their cash and checks (a transfer-only deposit is not migrated).
--   * Amounts are rounded to centavos. An application beyond what is left of the charge or the credit
--     (legacy allowed it; a few are typos of 10^9+) is cut to fit; an absurd tender amount
--     (over 100M and over 1000x what it paid) is cut to what it paid.
--   * Accounting start date: the earliest migrated AR/AP document, unless one was already set.
--     Migrated Purchase Invoices dated on/after it are tracked (open a payable).
-- ============================================================================================

-- ---- accounting settings --------------------------------------------------------------------

CREATE TABLE migration.cp AS
SELECT p.*, migration.seq_day(p.date, NULL, max(migration.day(p.date)) OVER (ORDER BY p.id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING)) AS day,
       coalesce(p.customercmmasterid, 0) <> 0 AS memo_carrier
FROM legacy.tblcustomerpayments p
WHERE EXISTS (SELECT 1 FROM customers c WHERE c.id = p.customerid);
CREATE UNIQUE INDEX ON migration.cp (id);

CREATE TABLE migration.cm AS
SELECT m.*, migration.seq_day(m.date, NULL, max(migration.day(m.date)) OVER (ORDER BY m.id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING)) AS day
FROM legacy.tblcustomercreditmemos m
WHERE EXISTS (SELECT 1 FROM customers c WHERE c.id = m.customerid);
CREATE UNIQUE INDEX ON migration.cm (id);
CREATE INDEX ON migration.cm (customerpaymentdetailid);

CREATE TABLE migration.dep AS
SELECT d.*, migration.seq_day(d.date, NULL, max(migration.day(d.date)) OVER (ORDER BY d.id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING)) AS day
FROM legacy.tbldeposits d
WHERE EXISTS (SELECT 1 FROM bank_accounts a WHERE a.id = d.bankid);
CREATE UNIQUE INDEX ON migration.dep (id);

CREATE TABLE migration.sp AS
SELECT p.*, migration.doc_day(p.date, NULL) AS day
FROM legacy.tblsupplierpayments p
WHERE EXISTS (SELECT 1 FROM suppliers s WHERE s.id = p.supplierid);

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'skipped', 'CustomerPayment', p.id, 'Customer ' || coalesce(migration.legacy_id(p.customerid)::text, 'NULL') || ' does not exist'
FROM legacy.tblcustomerpayments p WHERE NOT EXISTS (SELECT 1 FROM migration.cp c WHERE c.id = p.id)
UNION ALL
SELECT 'skipped', 'CustomerCreditMemo', m.id, 'Customer ' || coalesce(migration.legacy_id(m.customerid)::text, 'NULL') || ' does not exist'
FROM legacy.tblcustomercreditmemos m WHERE NOT EXISTS (SELECT 1 FROM migration.cm c WHERE c.id = m.id)
UNION ALL
SELECT 'skipped', 'Deposit', d.id, 'Bank account ' || coalesce(migration.legacy_id(d.bankid)::text, 'NULL') || ' does not exist'
FROM legacy.tbldeposits d WHERE NOT EXISTS (SELECT 1 FROM migration.dep x WHERE x.id = d.id)
UNION ALL
SELECT 'skipped', 'SupplierPayment', p.id, 'Supplier ' || coalesce(migration.legacy_id(p.supplierid)::text, 'NULL') || ' does not exist'
FROM legacy.tblsupplierpayments p WHERE NOT EXISTS (SELECT 1 FROM migration.sp s WHERE s.id = p.id)
UNION ALL
SELECT 'skipped', 'CustomerDebitMemo', d.id, 'Customer ' || coalesce(migration.legacy_id(d.customerid)::text, 'NULL') || ' does not exist'
FROM legacy.tblcustomerdebitmemos d WHERE NOT EXISTS (SELECT 1 FROM customers c WHERE c.id = d.customerid)
UNION ALL
SELECT 'skipped', 'CustomerRefund', d.id, 'Customer ' || coalesce(migration.legacy_id(d.customerid)::text, 'NULL') || ' does not exist'
FROM legacy.tblcustomerrefunds d WHERE NOT EXISTS (SELECT 1 FROM customers c WHERE c.id = d.customerid);

INSERT INTO accounting_settings (company_id, start_date, check_clearing_days, created_at, updated_at, created_by, updated_by)
SELECT migration.cid(), x.start_date, 3, now(), now(), 'legacy-migration', 'legacy-migration'
FROM (SELECT least((SELECT min(day) FROM migration.si WHERE NOT void),
                   (SELECT min(day) FROM migration.cp WHERE NOT void),
                   (SELECT min(day) FROM migration.cm WHERE NOT void),
                   (SELECT min(day) FROM migration.sir WHERE NOT void),
                   (SELECT min(day) FROM migration.dep WHERE NOT void),
                   (SELECT min(day) FROM migration.sp WHERE NOT void),
                   (SELECT min(invoice_date) FROM purchase_invoices WHERE company_id = migration.cid() AND NOT voided
                    AND EXISTS (SELECT 1 FROM migration.sp))) AS start_date) x
WHERE x.start_date IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM accounting_settings s WHERE s.company_id = migration.cid());

ANALYZE sales_invoices; ANALYZE sales_invoice_lines; ANALYZE purchase_invoices; ANALYZE customers;

-- ---- customer memos -------------------------------------------------------------------------

-- A return slip's credit memo is the Sales Invoice Return's credit, not a memo of its own.
CREATE TABLE migration.cm_sir AS SELECT memo_id, id AS sir_id FROM migration.sir WHERE memo_id IS NOT NULL;
CREATE UNIQUE INDEX ON migration.cm_sir (memo_id);

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'merged', 'CustomerCreditMemo', x.memo_id, 'Sales Return memo of return slip ' || coalesce(btrim(r.sirno), migration.legacy_id(r.id)::text)
       || ': it is that Sales Invoice Return''s credit'
FROM migration.cm_sir x JOIN migration.sir r ON r.id = x.sir_id;

-- Legacy credit memo types -> Norbiz reasons; the type name is kept in the line and remarks.
CREATE FUNCTION migration.cm_reason(type_name text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN type_name ILIKE 'sales return%' THEN 'SALES_RETURN'
                WHEN type_name ILIKE '%rebate%' THEN 'REBATE'
                WHEN type_name ILIKE 'discount%' OR type_name ILIKE '%deal%' OR type_name ILIKE 'promo%'
                     OR type_name ILIKE '%charge%' OR type_name ILIKE 'tax certificate%' THEN 'PRICE_ADJUSTMENT'
                ELSE 'OTHER' END
$$;

INSERT INTO customer_memos (id, company_id, customer_id, memo_type, reason, reference_number, sheet_number, memo_date, amount,
                            term_days, due_date, original_reference, remarks, created_at, created_by, voided, voided_at, voided_by, origin)
SELECT m.id, migration.cid(), m.customerid, 'CREDIT', migration.cm_reason(t.name),
       CASE WHEN row_number() OVER (PARTITION BY btrim(m.cmno) ORDER BY m.id) > 1 OR migration.trunc(m.cmno, 50) IS NULL
            THEN left(coalesce(btrim(m.cmno), 'CCM'), 38) || '-' || migration.legacy_id(m.id) ELSE btrim(m.cmno) END,
       NULL, m.day, round(greatest(coalesce(m.netamount, 0), 0), 2), 0, NULL, migration.trunc(m.refno, 100),
       migration.trunc(concat_ws(' — ', nullif(btrim(t.name), ''), nullif(btrim(m.comments), '')), 255),
       migration.created(NULL, m.date), migration.actor(m.encodedbyid),
       m.void, CASE WHEN m.void THEN migration.created(NULL, m.date) END, CASE WHEN m.void THEN 'legacy-migration' END, 'MIGRATED'
FROM migration.cm m LEFT JOIN legacy.tblcustomercreditmemotypes t ON t.id = m.cmtypeid
WHERE NOT EXISTS (SELECT 1 FROM migration.cm_sir x WHERE x.memo_id = m.id);

INSERT INTO customer_memo_lines (customer_memo_id, description, amount, line_number)
SELECT m.id, left(coalesce(nullif(btrim(t.name), ''), 'Credit memo') || coalesce(' — ' || nullif(btrim(m.refno), ''), ''), 255),
       round(greatest(coalesce(m.netamount, 0), 0), 2), 1
FROM migration.cm m LEFT JOIN legacy.tblcustomercreditmemotypes t ON t.id = m.cmtypeid
WHERE NOT EXISTS (SELECT 1 FROM migration.cm_sir x WHERE x.memo_id = m.id);

-- Debit memos share the table: IDs + 1,000,000 keep them clear of the credit memos'.
INSERT INTO customer_memos (id, company_id, customer_id, memo_type, reason, reference_number, sheet_number, memo_date, amount,
                            term_days, due_date, original_reference, remarks, created_at, created_by, voided, voided_at, voided_by, origin)
SELECT d.id + 1000000, migration.cid(), d.customerid, 'DEBIT', 'BILLING',
       CASE WHEN row_number() OVER (PARTITION BY btrim(d.cdmno) ORDER BY d.id) > 1 OR migration.trunc(d.cdmno, 50) IS NULL
            THEN left(coalesce(btrim(d.cdmno), 'CDM'), 38) || '-' || migration.legacy_id(d.id) ELSE btrim(d.cdmno) END,
       NULL, migration.doc_day(d.date, NULL), round(greatest(coalesce(d.netamount, 0), 0), 2), c.term_days,
       migration.doc_day(d.date, NULL) + make_interval(days => c.term_days), NULL, migration.trunc(d.description, 255),
       migration.created(NULL, d.date), migration.actor(d.encodedby),
       d.void, CASE WHEN d.void THEN migration.created(NULL, d.date) END, CASE WHEN d.void THEN 'legacy-migration' END, 'MIGRATED'
FROM legacy.tblcustomerdebitmemos d JOIN customers c ON c.id = d.customerid;

INSERT INTO customer_memo_lines (customer_memo_id, description, amount, line_number)
SELECT d.id + 1000000, left(coalesce(nullif(btrim(d.description), ''), 'Debit memo'), 255), round(greatest(coalesce(d.netamount, 0), 0), 2), 1
FROM legacy.tblcustomerdebitmemos d WHERE EXISTS (SELECT 1 FROM customer_memos m WHERE m.id = d.id + 1000000);

-- ---- customer payments and tenders ----------------------------------------------------------

CREATE FUNCTION migration.received_mode(mode int) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE mode WHEN 1 THEN 'CASH' WHEN 2 THEN 'CHECK' WHEN 3 THEN 'BANK_TRANSFER' WHEN 5 THEN 'CREDIT_CARD' ELSE 'OTHERS' END
$$;

-- Tenders: the details of real payments (memo carriers become memos above).
CREATE TABLE migration.tender AS
SELECT d.id, d.customerpaymentid AS payment_id, p.customerid AS customer_id, p.day AS payment_day,
       p.void OR coalesce(d.void, false) AS voided, coalesce(d.bounced, false) AS bounced, coalesce(d.cleared, false) AS cleared,
       migration.received_mode(d.modeofpaymentid) AS mode, round(greatest(coalesce(d.amount, 0), 0), 2) AS amount,
       migration.day(d.checkdate) AS check_date, migration.trunc(d.refno, 50) AS ref_no,
       migration.trunc(d.orno, 50) AS or_no, migration.trunc(d.prno, 50) AS pr_no,
       CASE WHEN EXISTS (SELECT 1 FROM banks b WHERE b.id = d.bankid) THEN d.bankid END AS bank_id,
       row_number() OVER (PARTITION BY d.customerpaymentid ORDER BY d.id) AS line_number,
       count(*) OVER (PARTITION BY d.customerpaymentid) AS tenders
FROM legacy.tblcustomerpaymentdetails d
JOIN migration.cp p ON p.id = d.customerpaymentid AND NOT p.memo_carrier
WHERE d.modeofpaymentid <> 101;
CREATE UNIQUE INDEX ON migration.tender (id);

-- The legacy deposits each tender went into (live deposit, live detail), latest first.
CREATE TABLE migration.tender_dep AS
SELECT x.paymentdetailid AS tender_id, x.depositid AS deposit_id, d.day, d.bankid AS bank_account_id,
       row_number() OVER (PARTITION BY x.paymentdetailid ORDER BY d.day DESC, d.id DESC) AS recency
FROM legacy.tbldepositdetails x JOIN migration.dep d ON d.id = x.depositid
WHERE NOT coalesce(x.void, false) AND NOT d.void;
CREATE INDEX ON migration.tender_dep (tender_id);

-- Norbiz deposits keep only cash and checks; a legacy deposit with none is not migrated.
CREATE TABLE migration.deposit_line AS
SELECT td.deposit_id, t.id AS tender_id, t.amount,
       row_number() OVER (PARTITION BY td.deposit_id ORDER BY t.id) AS line_number
FROM migration.tender_dep td JOIN migration.tender t ON t.id = td.tender_id
WHERE t.mode IN ('CASH', 'CHECK') AND NOT t.voided;
CREATE INDEX ON migration.deposit_line (deposit_id);
CREATE INDEX ON migration.tender (payment_id);
ANALYZE migration.tender; ANALYZE migration.tender_dep; ANALYZE migration.deposit_line;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'merged', 'Deposit', d.id, 'Held only bank transfers / cards; they are booked as transfer receipts into its account'
FROM migration.dep d WHERE NOT d.void AND NOT EXISTS (SELECT 1 FROM migration.deposit_line l WHERE l.deposit_id = d.id);

-- When a bounced check bounced (see the header).
CREATE TABLE migration.tender_state AS
SELECT t.id,
       CASE WHEN t.bounced AND t.mode IN ('CHECK', 'BANK_TRANSFER', 'CREDIT_CARD') THEN 'BOUNCED'
            WHEN t.mode NOT IN ('CASH', 'CHECK') THEN 'CLEARED'
            WHEN t.mode = 'CHECK' AND t.cleared THEN 'CLEARED'
            WHEN t.mode = 'CASH' AND d.deposit_id IS NOT NULL THEN 'CLEARED'
            WHEN d.deposit_id IS NOT NULL THEN 'DEPOSITED'
            WHEN t.mode = 'CASH' AND t.cleared THEN 'CLEARED'
            ELSE 'ON_HAND' END AS status,
       CASE WHEN t.bounced AND t.mode IN ('CHECK', 'BANK_TRANSFER', 'CREDIT_CARD') THEN greatest(t.payment_day, coalesce(d.day, t.check_date, t.payment_day))
            WHEN d.deposit_id IS NOT NULL THEN greatest(t.payment_day, d.day)
            ELSE t.payment_day END AS status_day,
       CASE WHEN t.mode IN ('CASH', 'CHECK') AND EXISTS (SELECT 1 FROM migration.deposit_line l WHERE l.deposit_id = d.deposit_id) THEN d.deposit_id END AS deposit_id,
       d.bank_account_id, d.day AS deposit_day
FROM migration.tender t
LEFT JOIN migration.tender_dep d ON d.tender_id = t.id AND d.recency = 1;
CREATE UNIQUE INDEX ON migration.tender_state (id);

INSERT INTO customer_payments (id, company_id, customer_id, reference_number, sheet_number, payment_date, total_amount, remarks,
                               created_at, created_by, voided, voided_at, voided_by, origin)
SELECT p.id, migration.cid(), p.customerid,
       CASE WHEN row_number() OVER (PARTITION BY btrim(p.cpyno) ORDER BY p.id) > 1 OR migration.trunc(p.cpyno, 50) IS NULL
            THEN left(coalesce(btrim(p.cpyno), 'CPY'), 38) || '-' || migration.legacy_id(p.id) ELSE btrim(p.cpyno) END,
       NULL, p.day, coalesce((SELECT sum(t.amount) FROM migration.tender t WHERE t.payment_id = p.id), 0),
       migration.trunc(regexp_replace(p.comments, '\s+', ' ', 'g'), 255),
       migration.created(NULL, p.date), migration.actor(p.encodedbyid),
       p.void, CASE WHEN p.void THEN migration.created(NULL, p.date) END, CASE WHEN p.void THEN 'legacy-migration' END, 'MIGRATED'
FROM migration.cp p WHERE NOT p.memo_carrier;

INSERT INTO deposits (id, company_id, bank_account_id, reference_number, sheet_number, deposit_date, total_amount, remarks,
                      created_at, created_by, voided, voided_at, voided_by, origin)
SELECT d.id, migration.cid(), d.bankid,
       CASE WHEN row_number() OVER (PARTITION BY btrim(d.depositno) ORDER BY d.id) > 1 OR migration.trunc(d.depositno, 50) IS NULL
            THEN left(coalesce(btrim(d.depositno), 'DEP'), 38) || '-' || migration.legacy_id(d.id) ELSE btrim(d.depositno) END,
       NULL, d.day, coalesce((SELECT sum(l.amount) FROM migration.deposit_line l WHERE l.deposit_id = d.id), 0),
       migration.trunc(concat_ws(' — ', nullif(btrim(d.description), ''), nullif(btrim(regexp_replace(d.comments, '\s+', ' ', 'g')), '')), 255),
       migration.created(NULL, d.date), migration.actor(d.encodedby),
       d.void, CASE WHEN d.void THEN migration.created(NULL, d.date) END, CASE WHEN d.void THEN 'legacy-migration' END, 'MIGRATED'
FROM migration.dep d
WHERE d.void OR EXISTS (SELECT 1 FROM migration.deposit_line l WHERE l.deposit_id = d.id);

INSERT INTO customer_payment_tenders (id, customer_payment_id, line_number, mode, amount, replaced_amount, bank_id, check_number, check_date,
                                      official_receipt_number, provisional_receipt_number, status, status_date, bounce_reason,
                                      deposit_id, deposit_bank_account_id)
SELECT t.id, t.payment_id, t.line_number, t.mode, t.amount, 0, CASE WHEN t.mode = 'CHECK' THEN t.bank_id END,
       t.ref_no, CASE WHEN t.mode = 'CHECK' THEN coalesce(t.check_date, t.payment_day) END,
       t.or_no, t.pr_no, s.status, s.status_day, CASE WHEN s.status = 'BOUNCED' THEN 'OTHER' END,
       s.deposit_id,
       CASE WHEN s.deposit_id IS NOT NULL OR t.mode IN ('BANK_TRANSFER', 'CREDIT_CARD') THEN s.bank_account_id END
FROM migration.tender t JOIN migration.tender_state s ON s.id = t.id;

INSERT INTO deposit_lines (deposit_id, tender_id, amount, line_number)
SELECT l.deposit_id, l.tender_id, l.amount, l.line_number
FROM migration.deposit_line l WHERE EXISTS (SELECT 1 FROM deposits d WHERE d.id = l.deposit_id);

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'no-bank-account', 'CustomerPaymentTender', t.id, t.mode || ' tender never reached a legacy deposit: no bank account, no bank-book entry'
FROM migration.tender t JOIN migration.tender_state s ON s.id = t.id
WHERE t.mode IN ('BANK_TRANSFER', 'CREDIT_CARD') AND NOT t.voided AND s.bank_account_id IS NULL
UNION ALL
SELECT 'bounced', 'CustomerPaymentTender', t.id,
       CASE WHEN t.mode = 'CHECK' THEN 'Bounced check' ELSE 'Returned ' || lower(t.mode) || ' (Norbiz bounces checks only; kept as BOUNCED)' END
       || ': legacy keeps no bounce date, used ' || s.status_day::date
FROM migration.tender t JOIN migration.tender_state s ON s.id = t.id WHERE s.status = 'BOUNCED' AND NOT t.voided;

-- ---- customer refunds -------------------------------------------------------------------------

INSERT INTO customer_refunds (id, company_id, customer_id, reference_number, sheet_number, refund_date, amount, mode, status, status_date,
                              remarks, created_at, created_by, voided, voided_at, voided_by, origin)
SELECT r.id, migration.cid(), r.customerid, btrim(r.refundno), migration.trunc(r.refno, 100), migration.doc_day(r.date, NULL),
       round(greatest(coalesce(r.netamount, 0), 0), 2), 'CASH', 'CLEARED', migration.doc_day(r.date, NULL),
       migration.trunc(concat_ws(' — ', 'Paid by: unknown (legacy keeps no instrument; migrated as cash)', nullif(btrim(r.comments), '')), 255),
       migration.created(NULL, r.date), migration.actor(r.encodedbyid),
       r.void, CASE WHEN r.void THEN migration.created(NULL, r.date) END, CASE WHEN r.void THEN 'legacy-migration' END, 'MIGRATED'
FROM legacy.tblcustomerrefunds r JOIN customers c ON c.id = r.customerid;

-- ---- supplier payments and tenders ----------------------------------------------------------

CREATE TABLE migration.sp_tender AS
SELECT d.id, d.supplierpaymentid AS payment_id, p.supplierid AS supplier_id, p.day AS payment_day,
       p.void OR coalesce(d.void, false) AS voided,
       CASE d.modeofpaymentid WHEN 1 THEN 'CASH' WHEN 2 THEN 'CHECK' WHEN 3 THEN 'BANK_TRANSFER' ELSE 'OTHERS' END AS mode,
       round(greatest(coalesce(d.amount, 0), 0), 2) AS amount, coalesce(d.cleared, false) AS cleared,
       migration.day(d.checkdate) AS check_date, migration.trunc(d.refno, 50) AS ref_no,
       CASE WHEN EXISTS (SELECT 1 FROM bank_accounts a WHERE a.id = d.bankid) THEN d.bankid END AS bank_account_id,
       row_number() OVER (PARTITION BY d.supplierpaymentid ORDER BY d.id) AS line_number,
       count(*) OVER (PARTITION BY d.supplierpaymentid) AS tenders
FROM legacy.tblsupplierpaymentdetails d JOIN migration.sp p ON p.id = d.supplierpaymentid;
CREATE UNIQUE INDEX ON migration.sp_tender (id);
CREATE INDEX ON migration.sp_tender (payment_id);

-- A check or transfer drawn on no known account of ours can't post to the bank book: kept as OTHERS.
UPDATE migration.sp_tender SET mode = 'OTHERS' WHERE mode IN ('CHECK', 'BANK_TRANSFER') AND bank_account_id IS NULL;

INSERT INTO supplier_payments (id, company_id, supplier_id, reference_number, voucher_number, sheet_number, payment_date, total_amount,
                               remarks, created_at, created_by, voided, voided_at, voided_by, origin)
SELECT p.id, migration.cid(), p.supplierid, btrim(p.spyno), migration.trunc(p.vouno, 50), NULL, p.day,
       coalesce((SELECT sum(t.amount) FROM migration.sp_tender t WHERE t.payment_id = p.id), 0),
       migration.trunc(regexp_replace(p.comments, '\s+', ' ', 'g'), 255),
       migration.created(NULL, p.date), migration.actor(p.encodedbyid),
       p.void, CASE WHEN p.void THEN migration.created(NULL, p.date) END, CASE WHEN p.void THEN 'legacy-migration' END, 'MIGRATED'
FROM migration.sp p;

INSERT INTO supplier_payment_tenders (id, supplier_payment_id, line_number, mode, amount, bank_account_id, check_number, check_date,
                                      status, status_date)
SELECT t.id, t.payment_id, t.line_number, t.mode, t.amount, CASE WHEN t.mode IN ('CHECK', 'BANK_TRANSFER') THEN t.bank_account_id END,
       t.ref_no, CASE WHEN t.mode = 'CHECK' THEN coalesce(t.check_date, t.payment_day) END,
       CASE WHEN t.mode = 'CHECK' AND NOT t.cleared THEN 'ISSUED' ELSE 'CLEARED' END, t.payment_day
FROM migration.sp_tender t;

ANALYZE customer_memos; ANALYZE customer_memo_lines; ANALYZE customer_payments; ANALYZE customer_payment_tenders; ANALYZE deposits;
ANALYZE deposit_lines; ANALYZE customer_refunds; ANALYZE supplier_payments; ANALYZE supplier_payment_tenders;

-- ============================================================================================
-- Open items and allocations
-- ============================================================================================

-- Every charge and credit, keyed by (source_type, source_id) as OpenItemService keys them.
CREATE TABLE migration.oi (
    source_type text NOT NULL, source_id bigint NOT NULL, side text NOT NULL, direction text NOT NULL,
    customer_id bigint, supplier_id bigint, reference_number text NOT NULL, document_date timestamptz NOT NULL,
    due_date timestamptz, amount numeric(19, 2) NOT NULL, cleared boolean NOT NULL, live boolean NOT NULL,
    void_reason text, voided_date timestamptz, created_at timestamptz NOT NULL, created_by text,
    PRIMARY KEY (source_type, source_id));

INSERT INTO migration.oi
SELECT 'SALES_INVOICE', s.id, 'RECEIVABLE', 'CHARGE', s.customer_id, NULL, s.reference_number, s.invoice_date, s.due_date, s.net_amount,
       true, true, NULL, NULL, s.created_at, s.created_by
FROM sales_invoices s WHERE s.company_id = migration.cid() AND s.origin = 'MIGRATED' AND NOT s.voided AND s.net_amount > 0;

INSERT INTO migration.oi
SELECT CASE m.memo_type WHEN 'DEBIT' THEN 'CUSTOMER_DEBIT_MEMO' ELSE 'CUSTOMER_CREDIT_MEMO' END, m.id, 'RECEIVABLE',
       CASE m.memo_type WHEN 'DEBIT' THEN 'CHARGE' ELSE 'CREDIT' END, m.customer_id, NULL, m.reference_number, m.memo_date,
       CASE m.memo_type WHEN 'DEBIT' THEN m.due_date END, m.amount, true, true, NULL, NULL, m.created_at, m.created_by
FROM customer_memos m WHERE m.company_id = migration.cid() AND m.origin = 'MIGRATED' AND NOT m.voided AND m.amount > 0;

INSERT INTO migration.oi
SELECT 'SALES_INVOICE_RETURN', r.id, 'RECEIVABLE', 'CREDIT', r.customer_id, NULL, r.reference_number, r.return_date, NULL, r.net_amount,
       true, true, NULL, NULL, r.created_at, r.created_by
FROM sales_invoice_returns r JOIN migration.cm_sir x ON x.sir_id = r.id
WHERE r.company_id = migration.cid() AND r.origin = 'MIGRATED' AND NOT r.voided AND r.net_amount > 0;

INSERT INTO migration.oi
SELECT 'CUSTOMER_REFUND', r.id, 'RECEIVABLE', 'CHARGE', r.customer_id, NULL, r.reference_number, r.refund_date, r.refund_date, r.amount,
       true, true, NULL, NULL, r.created_at, r.created_by
FROM customer_refunds r WHERE r.company_id = migration.cid() AND r.origin = 'MIGRATED' AND NOT r.voided AND r.amount > 0;

-- Tenders: a bounced check's credit stood until it bounced (VOIDED / BOUNCED).
INSERT INTO migration.oi
SELECT 'CUSTOMER_PAYMENT_TENDER', t.id, 'RECEIVABLE', 'CREDIT', t.customer_id, NULL,
       left(p.reference_number || CASE WHEN t.mode = 'CHECK' THEN ' #' || coalesce(t.ref_no, '') WHEN t.tenders > 1 THEN ' (' || t.mode || ' ' || t.line_number || ')' ELSE '' END, 100),
       t.payment_day, NULL, t.amount, s.status = 'CLEARED' OR t.mode <> 'CHECK', NOT t.voided AND s.status <> 'BOUNCED',
       CASE WHEN t.voided THEN 'VOIDED' WHEN s.status = 'BOUNCED' THEN 'BOUNCED' END,
       CASE WHEN t.voided THEN p.voided_at WHEN s.status = 'BOUNCED' THEN s.status_day END,
       p.created_at, p.created_by
FROM migration.tender t JOIN migration.tender_state s ON s.id = t.id JOIN customer_payments p ON p.id = t.payment_id
WHERE t.amount > 0;

-- Purchase invoices are tracked when dated on/after the accounting start date (docs/ACCOUNTING.md).
INSERT INTO migration.oi
SELECT 'PURCHASE_INVOICE', p.id, 'PAYABLE', 'CHARGE', NULL, p.supplier_id, p.reference_number, p.invoice_date, p.due_date, p.net_amount,
       true, true, NULL, NULL, p.created_at, p.created_by
FROM purchase_invoices p JOIN accounting_settings a ON a.company_id = p.company_id
WHERE p.company_id = migration.cid() AND p.origin = 'MIGRATED' AND NOT p.voided AND p.net_amount > 0 AND p.invoice_date >= a.start_date;

INSERT INTO migration.oi
SELECT 'SUPPLIER_PAYMENT_TENDER', t.id, 'PAYABLE', 'CREDIT', NULL, t.supplier_id,
       left(p.reference_number || CASE WHEN t.mode = 'CHECK' THEN ' #' || coalesce(t.ref_no, '') WHEN t.tenders > 1 THEN ' (' || t.mode || ' ' || t.line_number || ')' ELSE '' END, 100),
       t.payment_day, NULL, t.amount, NOT (t.mode = 'CHECK' AND NOT t.cleared), NOT t.voided,
       CASE WHEN t.voided THEN 'VOIDED' END, CASE WHEN t.voided THEN p.voided_at END, p.created_at, p.created_by
FROM migration.sp_tender t JOIN supplier_payments p ON p.id = t.payment_id
WHERE t.amount > 0;

-- Legacy links -> allocations (credit -> charge), before trimming.
CREATE TABLE migration.alloc (
    n bigserial, credit_type text NOT NULL, credit_id bigint NOT NULL, charge_type text NOT NULL, charge_id bigint NOT NULL,
    amount numeric NOT NULL, allocation_date timestamptz NOT NULL, source_type text NOT NULL, source_id bigint NOT NULL,
    reversed boolean NOT NULL, reversed_date timestamptz, legacy_link bigint, created_by text);

INSERT INTO migration.alloc (credit_type, credit_id, charge_type, charge_id, amount, allocation_date, source_type, source_id,
                             reversed, reversed_date, legacy_link, created_by)
SELECT CASE WHEN x.sir_id IS NOT NULL THEN 'SALES_INVOICE_RETURN' WHEN d.modeofpaymentid = 101 THEN 'CUSTOMER_CREDIT_MEMO' ELSE 'CUSTOMER_PAYMENT_TENDER' END,
       CASE WHEN x.sir_id IS NOT NULL THEN x.sir_id WHEN d.modeofpaymentid = 101 THEN m.id ELSE d.id END,
       CASE k.payabletypeid WHEN 1 THEN 'SALES_INVOICE' WHEN 2 THEN 'CUSTOMER_DEBIT_MEMO' WHEN 102 THEN 'CUSTOMER_REFUND' END,
       CASE WHEN k.payabletypeid = 2 THEN k.payableid + 1000000 ELSE k.payableid END,
       k.amount,
       CASE WHEN d.modeofpaymentid = 101 THEN m.day ELSE p.day END,
       CASE WHEN k.payabletypeid = 102 THEN 'CUSTOMER_REFUND' WHEN x.sir_id IS NOT NULL THEN 'SALES_INVOICE_RETURN'
            WHEN d.modeofpaymentid = 101 THEN 'CUSTOMER_MEMO' ELSE 'CUSTOMER_PAYMENT' END,
       CASE WHEN k.payabletypeid = 102 THEN k.payableid WHEN x.sir_id IS NOT NULL THEN x.sir_id WHEN d.modeofpaymentid = 101 THEN m.id ELSE p.id END,
       coalesce(s.status = 'BOUNCED', false),
       CASE WHEN s.status = 'BOUNCED' THEN s.status_day END,
       k.id, migration.actor(coalesce(k.encodedbyid, p.encodedbyid))
FROM legacy.tblcustomerpaymentlinks k
JOIN legacy.tblcustomerpaymentdetails d ON d.id = k.customerpaymentdetailid
JOIN migration.cp p ON p.id = d.customerpaymentid
LEFT JOIN migration.cm m ON d.modeofpaymentid = 101 AND m.customerpaymentdetailid = d.id
LEFT JOIN migration.cm_sir x ON x.memo_id = m.id
LEFT JOIN migration.tender_state s ON s.id = d.id
WHERE NOT p.void AND NOT coalesce(d.void, false) AND k.amount > 0 AND k.payabletypeid IN (1, 2, 102)
  AND (d.modeofpaymentid <> 101 OR m.id IS NOT NULL);

INSERT INTO migration.alloc (credit_type, credit_id, charge_type, charge_id, amount, allocation_date, source_type, source_id,
                             reversed, reversed_date, legacy_link, created_by)
SELECT 'SUPPLIER_PAYMENT_TENDER', d.id, 'PURCHASE_INVOICE', k.payableid, k.amount, p.day, 'SUPPLIER_PAYMENT', p.id,
       false, NULL, k.id, migration.actor(coalesce(k.encodedbyid, p.encodedbyid))
FROM legacy.tblsupplierpaymentlinks k
JOIN legacy.tblsupplierpaymentdetails d ON d.id = k.supplierpaymentdetailid
JOIN migration.sp p ON p.id = d.supplierpaymentid
WHERE NOT p.void AND NOT coalesce(d.void, false) AND k.amount > 0 AND k.payabletypeid = 1;

-- Legacy amounts have 4 decimals, Norbiz 2. Rounding each application on its own leaves fully paid
-- charges a centavo short; rounding each charge's running total instead makes its applications add
-- up to the legacy total rounded once.
CREATE UNIQUE INDEX ON migration.alloc (n);
UPDATE migration.alloc a SET amount = x.rounded
FROM (SELECT n, round(sum(amount) OVER w, 2) - round(sum(amount) OVER w - amount, 2) AS rounded
      FROM migration.alloc WINDOW w AS (PARTITION BY charge_type, charge_id, reversed ORDER BY allocation_date, n ROWS UNBOUNDED PRECEDING)) x
WHERE x.n = a.n;
DELETE FROM migration.alloc WHERE amount <= 0;

CREATE INDEX ON migration.alloc (credit_type, credit_id);
CREATE INDEX ON migration.alloc (charge_type, charge_id);
ANALYZE migration.oi; ANALYZE migration.alloc;

-- Applications to or from something that isn't a live open item (voided in legacy, zero value,
-- untracked invoice) can't be made.
INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'skipped', 'PaymentLink', a.legacy_link,
       'Applies ' || a.amount || ' from ' || a.credit_type || ' ' || migration.legacy_id(a.credit_id) || ' to ' || a.charge_type || ' '
       || migration.legacy_id(a.charge_id) || ': ' || CASE WHEN cr.source_id IS NULL OR NOT cr.live AND NOT a.reversed THEN 'the credit' ELSE 'the charge' END
       || ' is voided, zero or untracked in Norbiz'
FROM migration.alloc a
LEFT JOIN migration.oi cr ON cr.source_type = a.credit_type AND cr.source_id = a.credit_id
LEFT JOIN migration.oi ch ON ch.source_type = a.charge_type AND ch.source_id = a.charge_id
WHERE cr.source_id IS NULL OR ch.source_id IS NULL OR NOT ch.live OR (NOT cr.live AND NOT a.reversed);

DELETE FROM migration.alloc a
WHERE NOT EXISTS (SELECT 1 FROM migration.oi c WHERE c.source_type = a.credit_type AND c.source_id = a.credit_id AND (c.live OR a.reversed))
   OR NOT EXISTS (SELECT 1 FROM migration.oi c WHERE c.source_type = a.charge_type AND c.source_id = a.charge_id AND c.live);

-- Absurd tender amounts (legacy typos of 10^9+): cut to what the tender paid, once the charges cap it.
-- Then trim over-applications: by charge (oldest first keep), then by credit.
CREATE FUNCTION migration.trim_allocations() RETURNS void LANGUAGE sql AS $$
    WITH c AS (
        SELECT a.n, a.amount, o.amount AS cap,
               sum(a.amount) OVER (PARTITION BY a.charge_type, a.charge_id ORDER BY a.allocation_date, a.n ROWS UNBOUNDED PRECEDING) AS cum
        FROM migration.alloc a JOIN migration.oi o ON o.source_type = a.charge_type AND o.source_id = a.charge_id
        WHERE NOT a.reversed)
    UPDATE migration.alloc a SET amount = greatest(0, least(c.amount, c.cap - (c.cum - c.amount)))
    FROM c WHERE c.n = a.n AND c.cum > c.cap;
    WITH c AS (
        SELECT a.n, a.amount, o.amount AS cap,
               sum(a.amount) OVER (PARTITION BY a.credit_type, a.credit_id ORDER BY a.allocation_date, a.n ROWS UNBOUNDED PRECEDING) AS cum
        FROM migration.alloc a JOIN migration.oi o ON o.source_type = a.credit_type AND o.source_id = a.credit_id
        WHERE NOT a.reversed)
    UPDATE migration.alloc a SET amount = greatest(0, least(c.amount, c.cap - (c.cum - c.amount)))
    FROM c WHERE c.n = a.n AND c.cum > c.cap;
    -- A bounced check's (reversed) applications: each within both items.
    UPDATE migration.alloc a SET amount = least(a.amount, cr.amount, ch.amount)
    FROM migration.oi cr, migration.oi ch
    WHERE a.reversed AND cr.source_type = a.credit_type AND cr.source_id = a.credit_id
      AND ch.source_type = a.charge_type AND ch.source_id = a.charge_id AND a.amount > least(cr.amount, ch.amount);
$$;

CREATE TABLE migration.alloc_before AS SELECT n, amount FROM migration.alloc;
CREATE UNIQUE INDEX ON migration.alloc_before (n);
SELECT migration.trim_allocations();

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'absurd-amount', o.source_type, o.source_id,
       'Amount ' || o.amount || ' cut to ' || coalesce(x.applied, 0) || ' (what it actually paid)'
FROM migration.oi o
LEFT JOIN (SELECT credit_type, credit_id, sum(amount) applied FROM migration.alloc WHERE NOT reversed GROUP BY 1, 2) x
       ON x.credit_type = o.source_type AND x.credit_id = o.source_id
WHERE o.direction = 'CREDIT' AND o.amount > 100000000 AND o.amount > 1000 * greatest(coalesce(x.applied, 0), 1);

UPDATE migration.oi o SET amount = x.applied
FROM (SELECT credit_type, credit_id, sum(amount) applied FROM migration.alloc WHERE NOT reversed GROUP BY 1, 2) x
WHERE x.credit_type = o.source_type AND x.credit_id = o.source_id
  AND o.direction = 'CREDIT' AND o.amount > 100000000 AND o.amount > 1000 * greatest(x.applied, 1) AND x.applied > 0;
UPDATE customer_payment_tenders t SET amount = o.amount
FROM migration.oi o WHERE o.source_type = 'CUSTOMER_PAYMENT_TENDER' AND o.source_id = t.id AND t.amount <> o.amount;
UPDATE customer_payments p SET total_amount = x.total
FROM (SELECT customer_payment_id, sum(amount) total FROM customer_payment_tenders GROUP BY 1) x
WHERE x.customer_payment_id = p.id AND p.company_id = migration.cid() AND p.total_amount <> x.total;
UPDATE deposit_lines l SET amount = t.amount FROM customer_payment_tenders t WHERE t.id = l.tender_id AND l.amount <> t.amount;
UPDATE deposits d SET total_amount = x.total
FROM (SELECT deposit_id, sum(amount) total FROM deposit_lines GROUP BY 1) x
WHERE x.deposit_id = d.id AND d.company_id = migration.cid() AND d.total_amount <> x.total;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'trimmed', 'PaymentLink', a.legacy_link,
       'Applied ' || b.amount || ' but only ' || a.amount || ' was left on the ' || a.charge_type || ' / its credit'
FROM migration.alloc a JOIN migration.alloc_before b ON b.n = a.n WHERE a.amount < b.amount;
DELETE FROM migration.alloc WHERE amount <= 0;

-- ---- write them -----------------------------------------------------------------------------

INSERT INTO open_items (company_id, side, customer_id, supplier_id, direction, source_type, source_id, reference_number, document_date,
                        due_date, amount, applied_amount, pending_amount, bounced_amount, balance, cleared, status, void_reason, voided_date,
                        created_at, created_by)
SELECT migration.cid(), o.side, o.customer_id, o.supplier_id, o.direction, o.source_type, o.source_id, left(o.reference_number, 100),
       o.document_date, CASE WHEN o.direction = 'CHARGE' THEN coalesce(o.due_date, o.document_date) END, o.amount,
       coalesce(x.applied, 0), coalesce(x.pending, 0), coalesce(x.bounced, 0), o.amount - coalesce(x.applied, 0), o.cleared,
       CASE WHEN NOT o.live THEN 'VOIDED' WHEN o.amount - coalesce(x.applied, 0) = 0 THEN 'CLOSED' ELSE 'OPEN' END,
       o.void_reason, o.voided_date, o.created_at, o.created_by
FROM migration.oi o
LEFT JOIN (
    SELECT t, i, sum(applied) applied, sum(pending) pending, sum(bounced) bounced FROM (
        SELECT a.charge_type t, a.charge_id i,
               CASE WHEN NOT a.reversed THEN a.amount ELSE 0 END applied,
               CASE WHEN NOT a.reversed AND NOT cr.cleared THEN a.amount ELSE 0 END pending,
               CASE WHEN a.reversed THEN a.amount ELSE 0 END bounced
        FROM migration.alloc a JOIN migration.oi cr ON cr.source_type = a.credit_type AND cr.source_id = a.credit_id
        UNION ALL
        SELECT a.credit_type, a.credit_id, CASE WHEN NOT a.reversed THEN a.amount ELSE 0 END, 0, 0 FROM migration.alloc a
    ) y GROUP BY 1, 2) x ON x.t = o.source_type AND x.i = o.source_id
WHERE o.amount > 0;

INSERT INTO open_item_allocations (company_id, credit_item_id, charge_item_id, amount, allocation_date, source_type, source_id, pending,
                                   reversed, reversed_date, reversed_at, reversed_by, reversal_reason, created_at, created_by)
SELECT migration.cid(), cr.id, ch.id, a.amount, a.allocation_date, a.source_type, a.source_id, NOT cr.cleared,
       a.reversed, a.reversed_date, CASE WHEN a.reversed THEN a.reversed_date END, CASE WHEN a.reversed THEN 'legacy-migration' END,
       CASE WHEN a.reversed THEN 'BOUNCED' END, a.allocation_date, a.created_by
FROM migration.alloc a
JOIN open_items cr ON cr.company_id = migration.cid() AND cr.source_type = a.credit_type AND cr.source_id = a.credit_id
JOIN open_items ch ON ch.company_id = migration.cid() AND ch.source_type = a.charge_type AND ch.source_id = a.charge_id
ORDER BY a.allocation_date, a.n;

ANALYZE open_items; ANALYZE open_item_allocations; ANALYZE customer_memos; ANALYZE customer_payments; ANALYZE customer_payment_tenders;
ANALYZE customer_refunds; ANALYZE deposits; ANALYZE deposit_lines; ANALYZE supplier_payments; ANALYZE supplier_payment_tenders;
ANALYZE sales_invoices; ANALYZE purchase_invoices; ANALYZE sales_invoice_returns;

-- Documents point at their open item; invoices carry the payment status OpenItemService keeps.
UPDATE sales_invoices s SET open_item_id = o.id,
       payment_status = CASE WHEN o.applied_amount = 0 THEN 'UNPAID' WHEN o.balance = 0 THEN 'PAID' ELSE 'PARTIALLY_PAID' END
FROM open_items o WHERE o.company_id = migration.cid() AND o.source_type = 'SALES_INVOICE' AND o.source_id = s.id;
-- A zero-value invoice owes nothing.
UPDATE sales_invoices SET payment_status = 'PAID'
WHERE company_id = migration.cid() AND origin = 'MIGRATED' AND NOT voided AND net_amount = 0;
UPDATE purchase_invoices p SET open_item_id = o.id,
       payment_status = CASE WHEN o.applied_amount = 0 THEN 'UNPAID' WHEN o.balance = 0 THEN 'PAID' ELSE 'PARTIALLY_PAID' END
FROM open_items o WHERE o.company_id = migration.cid() AND o.source_type = 'PURCHASE_INVOICE' AND o.source_id = p.id;
UPDATE customer_memos m SET open_item_id = o.id
FROM open_items o WHERE o.company_id = migration.cid() AND o.source_type IN ('CUSTOMER_DEBIT_MEMO', 'CUSTOMER_CREDIT_MEMO') AND o.source_id = m.id;
UPDATE sales_invoice_returns r SET open_item_id = o.id
FROM open_items o WHERE o.company_id = migration.cid() AND o.source_type = 'SALES_INVOICE_RETURN' AND o.source_id = r.id;
UPDATE customer_refunds r SET open_item_id = o.id
FROM open_items o WHERE o.company_id = migration.cid() AND o.source_type = 'CUSTOMER_REFUND' AND o.source_id = r.id;
UPDATE customer_payment_tenders t SET open_item_id = o.id
FROM open_items o WHERE o.company_id = migration.cid() AND o.source_type = 'CUSTOMER_PAYMENT_TENDER' AND o.source_id = t.id;
UPDATE supplier_payment_tenders t SET open_item_id = o.id
FROM open_items o WHERE o.company_id = migration.cid() AND o.source_type = 'SUPPLIER_PAYMENT_TENDER' AND o.source_id = t.id;

-- Refund lines: the credits each refund used.
INSERT INTO customer_refund_lines (customer_refund_id, credit_item_id, amount, line_number)
SELECT a.source_id, cr.id, a.amount, row_number() OVER (PARTITION BY a.source_id ORDER BY a.n)
FROM migration.alloc a JOIN open_items cr ON cr.company_id = migration.cid() AND cr.source_type = a.credit_type AND cr.source_id = a.credit_id
WHERE a.source_type = 'CUSTOMER_REFUND';

-- ============================================================================================
-- Bank book
-- ============================================================================================

INSERT INTO bank_entries (company_id, bank_account_id, entry_date, entry_type, amount, source_type, source_id, source_line_id,
                          reference_number, check_number, check_date, description, cleared, voided, created_at, created_by)
SELECT migration.cid(), d.bank_account_id, d.deposit_date, 'DEPOSIT', d.total_amount, 'DEPOSIT', d.id, NULL, d.reference_number, NULL, NULL,
       (SELECT count(*) FROM deposit_lines l WHERE l.deposit_id = d.id) || ' item(s)', false, false, d.created_at, d.created_by
FROM deposits d WHERE d.company_id = migration.cid() AND d.origin = 'MIGRATED' AND NOT d.voided AND d.total_amount > 0
UNION ALL
-- A deposited check that bounced comes back out of the account.
SELECT migration.cid(), d.bank_account_id, t.status_date, 'CHECK_RETURNED', -t.amount, 'DEPOSIT', d.id, t.id, d.reference_number,
       t.check_number, t.check_date, left(c.name || ' — returned ' || t.bounce_reason, 255), false, false, d.created_at, 'legacy-migration'
FROM customer_payment_tenders t JOIN deposits d ON d.id = t.deposit_id JOIN customer_payments p ON p.id = t.customer_payment_id
JOIN customers c ON c.id = p.customer_id
WHERE p.company_id = migration.cid() AND t.status = 'BOUNCED' AND NOT d.voided AND NOT p.voided
UNION ALL
-- Transfers and cards land in the account of the legacy deposit that carried them, on its date.
SELECT migration.cid(), t.deposit_bank_account_id, greatest(p.payment_date, coalesce(s.deposit_day, p.payment_date)), 'TRANSFER_RECEIPT', t.amount,
       'CUSTOMER_PAYMENT', p.id, t.id, p.reference_number, t.check_number, NULL, left(c.name, 255), false, false, p.created_at, p.created_by
FROM customer_payment_tenders t JOIN customer_payments p ON p.id = t.customer_payment_id JOIN customers c ON c.id = p.customer_id
JOIN migration.tender_state s ON s.id = t.id
WHERE p.company_id = migration.cid() AND p.origin = 'MIGRATED' AND NOT p.voided AND t.mode IN ('BANK_TRANSFER', 'CREDIT_CARD')
  AND t.deposit_bank_account_id IS NOT NULL AND t.amount > 0 AND t.status <> 'BOUNCED'
UNION ALL
SELECT migration.cid(), t.bank_account_id, p.payment_date, CASE t.mode WHEN 'CHECK' THEN 'CHECK_DISBURSEMENT' ELSE 'TRANSFER_DISBURSEMENT' END,
       -t.amount, 'SUPPLIER_PAYMENT', p.id, t.id, p.reference_number, t.check_number, t.check_date, left(s.name, 255), false, false,
       p.created_at, p.created_by
FROM supplier_payment_tenders t JOIN supplier_payments p ON p.id = t.supplier_payment_id JOIN suppliers s ON s.id = p.supplier_id
WHERE p.company_id = migration.cid() AND p.origin = 'MIGRATED' AND NOT p.voided AND t.mode IN ('CHECK', 'BANK_TRANSFER') AND t.amount > 0;

-- Each account's opening date: its first book entry.
UPDATE bank_accounts a SET opening_balance_date = x.first_day
FROM (SELECT bank_account_id, min(entry_date) first_day FROM bank_entries WHERE company_id = migration.cid() GROUP BY 1) x
WHERE x.bank_account_id = a.id AND a.company_id = migration.cid() AND a.created_by = 'legacy-migration';

-- Bounced checks get the STATUS_CHANGED history CheckStatusService writes.
INSERT INTO transaction_events (company_id, transaction_type, transaction_id, reference_number, event_type, performed_by, performed_at, remarks)
SELECT migration.cid(), 'CUSTOMER_PAYMENT', p.id, left(p.reference_number, 50), 'STATUS_CHANGED', 'legacy-migration', t.status_date,
       left(CASE WHEN t.mode = 'CHECK' THEN 'Check #' || coalesce(t.check_number, '') ELSE initcap(replace(t.mode, '_', ' ')) || ' tender ' || t.line_number END
            || ' BOUNCED (OTHER) — migrated from legacy, which keeps no bounce date', 255)
FROM customer_payment_tenders t JOIN customer_payments p ON p.id = t.customer_payment_id
WHERE p.company_id = migration.cid() AND p.origin = 'MIGRATED' AND t.status = 'BOUNCED' AND NOT p.voided;
