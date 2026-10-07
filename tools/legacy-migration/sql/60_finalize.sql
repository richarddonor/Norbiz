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
    SELECT 'DELIVERY_RECEIPT' type, 'DR' prefix, reference_number FROM delivery_receipts
    UNION ALL SELECT 'OUTLET_RECEIVE', 'OR', reference_number FROM outlet_receives
    UNION ALL SELECT 'OUTLET_DELIVERY_RECEIPT', 'ODR', reference_number FROM outlet_delivery_receipts
    UNION ALL SELECT 'OUTLET_DELIVERY_RETURN', 'ODRR', reference_number FROM outlet_delivery_returns
    UNION ALL SELECT 'STOCK_TRANSFER', 'STF', reference_number FROM stock_transfers
    UNION ALL SELECT 'OUTLET_PULL_OUT', 'DRR', reference_number FROM outlet_pull_outs
    UNION ALL SELECT 'PULL_OUT_RECEIVE', 'OPO', reference_number FROM pull_out_receives
    UNION ALL SELECT 'ASSEMBLY', 'ASM', reference_number FROM assemblies
    UNION ALL SELECT 'INVENTORY_ADJUSTMENT', 'IA', reference_number FROM inventory_adjustments
    UNION ALL SELECT 'PURCHASE_INVOICE', 'PINV', reference_number FROM purchase_invoices
    UNION ALL SELECT 'PURCHASE_RECEIVE', 'PR', reference_number FROM purchase_receives
) s
GROUP BY s.type;

INSERT INTO audit_logs (entity_type, entity_id, action, changed_by, changed_at, changes)
SELECT 'Company', migration.cid(), 'CREATE', (SELECT actor FROM migration.ctx), now(),
       json_build_object('migration', 'Legacy jbsKarutora data loaded by tools/legacy-migration',
                         'cutover', (SELECT cutover FROM migration.ctx))::text;

ANALYZE;
