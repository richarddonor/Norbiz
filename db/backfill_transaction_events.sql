-- ============================================================
-- One-time, idempotent backfill of transaction_events CREATED / VOIDED
-- history for transactions posted before docs/TRANSACTION_ACTIONS.md existed.
-- Safe to re-run: each insert skips rows that already have that event.
-- Usage: psql -U admin -d norbiz -f db/backfill_transaction_events.sql
-- ============================================================

DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT * FROM (VALUES
        ('inventory_adjustments', 'INVENTORY_ADJUSTMENT'),
        ('purchase_orders',       'PURCHASE_ORDER'),
        ('purchase_invoices',     'PURCHASE_INVOICE'),
        ('purchase_receipts',     'PURCHASE_RECEIPT'),
        ('delivery_receipts',     'DELIVERY_RECEIPT'),
        ('outlet_receipts',       'OUTLET_RECEIPT'),
        ('outlet_delivery_receipts', 'OUTLET_DELIVERY_RECEIPT'),
        ('outlet_delivery_returns',  'OUTLET_DELIVERY_RETURN'),
        ('stock_transfers',          'STOCK_TRANSFER'),
        ('outlet_pull_outs',         'OUTLET_PULL_OUT'),
        ('pull_out_receipts',        'PULL_OUT_RECEIPT'),
        ('assemblies',               'ASSEMBLY'),
        ('sales_invoices',           'SALES_INVOICE'),
        ('customer_memos',           'CUSTOMER_MEMO'),
        ('supplier_memos',           'SUPPLIER_MEMO'),
        ('customer_payments',        'CUSTOMER_PAYMENT'),
        ('supplier_payments',        'SUPPLIER_PAYMENT'),
        ('customer_refunds',         'CUSTOMER_REFUND'),
        ('supplier_refunds',         'SUPPLIER_REFUND'),
        ('deposits',                 'DEPOSIT'),
        ('bank_reconciliations',     'BANK_RECONCILIATION'),
        ('statements_of_account',    'STATEMENT_OF_ACCOUNT')
    ) AS v(table_name, transaction_type)
    LOOP
        EXECUTE format($sql$
            INSERT INTO transaction_events (company_id, transaction_type, transaction_id, reference_number,
                                            event_type, performed_by, performed_at)
            SELECT x.company_id, %L, x.id, x.reference_number, 'CREATED', COALESCE(x.created_by, 'system'), x.created_at
            FROM %I x
            WHERE NOT EXISTS (SELECT 1 FROM transaction_events e
                              WHERE e.transaction_type = %L AND e.transaction_id = x.id AND e.event_type = 'CREATED')
        $sql$, t.transaction_type, t.table_name, t.transaction_type);

        EXECUTE format($sql$
            INSERT INTO transaction_events (company_id, transaction_type, transaction_id, reference_number,
                                            event_type, performed_by, performed_at)
            SELECT x.company_id, %L, x.id, x.reference_number, 'VOIDED', COALESCE(x.voided_by, 'system'), COALESCE(x.voided_at, NOW())
            FROM %I x
            WHERE x.voided
              AND NOT EXISTS (SELECT 1 FROM transaction_events e
                              WHERE e.transaction_type = %L AND e.transaction_id = x.id AND e.event_type = 'VOIDED')
        $sql$, t.transaction_type, t.table_name, t.transaction_type);
    END LOOP;
END $$;
