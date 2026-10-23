-- ============================================================================================
-- 60 — Finalize: identity sequences past every explicit ID, reference numbering continuing after
-- the highest legacy number, and an audit marker. (Redis is flushed by run_migration.sh.)
-- ============================================================================================

DO $$
DECLARE t record;
BEGIN
    FOR t IN SELECT c.relname AS tbl, a.attname AS col
             FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = 'public' AND a.attidentity IN ('a', 'd') AND c.relkind = 'r'
    LOOP
        EXECUTE format('SELECT setval(pg_get_serial_sequence(%L, %L), coalesce((SELECT max(%I) FROM public.%I), 0) + 1, false)',
                       'public.' || t.tbl, t.col, t.col, t.tbl);
    END LOOP;
END $$;

-- Norbiz numbers new documents "<PREFIX>-<6 digits>" from transaction_sequences. Where legacy used
-- the same prefix, numbering continues after its highest number (e.g. DR-093121 -> DR-093122).
INSERT INTO transaction_sequences (company_id, transaction_type, last_number)
SELECT migration.cid(), s.type, coalesce(max(substring(s.reference_number FROM '^' || s.prefix || '-(\d+)$')::bigint), 0)
FROM (
    SELECT 'DELIVERY_RECEIPT' type, 'DR' prefix, reference_number, company_id FROM delivery_receipts
    UNION ALL SELECT 'OUTLET_RECEIPT', 'OR', reference_number, company_id FROM outlet_receipts
    UNION ALL SELECT 'OUTLET_DELIVERY_RECEIPT', 'ODR', reference_number, company_id FROM outlet_delivery_receipts
    UNION ALL SELECT 'OUTLET_DELIVERY_RETURN', 'ODRR', reference_number, company_id FROM outlet_delivery_returns
    UNION ALL SELECT 'STOCK_TRANSFER', 'STF', reference_number, company_id FROM stock_transfers
    UNION ALL SELECT 'OUTLET_PULL_OUT', 'DRR', reference_number, company_id FROM outlet_pull_outs
    UNION ALL SELECT 'PULL_OUT_RECEIPT', 'OPO', reference_number, company_id FROM pull_out_receipts
    UNION ALL SELECT 'ASSEMBLY', 'ASM', reference_number, company_id FROM assemblies
    UNION ALL SELECT 'INVENTORY_ADJUSTMENT', 'IA', reference_number, company_id FROM inventory_adjustments
    UNION ALL SELECT 'PURCHASE_INVOICE', 'PINV', reference_number, company_id FROM purchase_invoices
    UNION ALL SELECT 'PURCHASE_RECEIPT', 'PR', reference_number, company_id FROM purchase_receipts
    UNION ALL SELECT 'SALES_INVOICE', 'SI', reference_number, company_id FROM sales_invoices
    UNION ALL SELECT 'SALES_INVOICE_RETURN', 'SIR', reference_number, company_id FROM sales_invoice_returns
    UNION ALL SELECT 'CUSTOMER_MEMO_CREDIT', 'CCM', reference_number, company_id FROM customer_memos WHERE memo_type = 'CREDIT'
    UNION ALL SELECT 'CUSTOMER_MEMO_DEBIT', 'CDM', reference_number, company_id FROM customer_memos WHERE memo_type = 'DEBIT'
    UNION ALL SELECT 'CUSTOMER_PAYMENT', 'CPY', reference_number, company_id FROM customer_payments
    UNION ALL SELECT 'CUSTOMER_REFUND', 'CRF', reference_number, company_id FROM customer_refunds
    UNION ALL SELECT 'DEPOSIT', 'DEP', reference_number, company_id FROM deposits
    UNION ALL SELECT 'SUPPLIER_PAYMENT', 'SPY', reference_number, company_id FROM supplier_payments
) s
WHERE s.company_id = migration.cid()
GROUP BY s.type;

INSERT INTO audit_logs (entity_type, entity_id, action, changed_by, changed_at, changes)
SELECT 'Company', migration.cid(), 'CREATE', (SELECT actor FROM migration.ctx), now(),
       json_build_object('migration', 'Legacy ' || (SELECT legacy_database FROM migration.ctx) || ' data loaded by tools/legacy-migration',
                         'cutover', (SELECT cutover FROM migration.ctx), 'idOffset', (SELECT id_offset FROM migration.ctx))::text;

ANALYZE;
