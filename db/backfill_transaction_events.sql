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
        ('purchase_receives',     'PURCHASE_RECEIVE'),
        ('delivery_receipts',     'DELIVERY_RECEIPT'),
        ('outlet_receives',       'OUTLET_RECEIVE')
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
