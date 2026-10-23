-- One-off backfill for the Neostrata load of 2026-10-23 (prod, company 3): migrated voided deposits
-- came in with no lines and a zero total, because 35_accounting.sql built deposit lines from live
-- legacy deposits only. The loader is fixed; this gives the already-loaded voided slips the cash and
-- check lines legacy had on them, as DepositService.voidDeposit keeps them. Nothing else changes:
-- tenders stay unlinked from the voided deposit (deposit_id, status) and no bank entry is posted.
--
-- Reads the legacy copy the load left in the target DB (shifted IDs). Idempotent: only voided
-- MIGRATED deposits that still have no lines are touched.
--
-- Run (app may stay up; deposits aren't Redis-cached):
--   ssh rdonor@10.18.18.18 "docker exec -i norbiz-db-1 sh -c 'psql -v ON_ERROR_STOP=1 -U \"\$POSTGRES_USER\" -d norbiz'" \
--     < tools/legacy-migration/fixes/2026-10-23_neostrata_voided_deposit_lines.sql

BEGIN;

DO $$
BEGIN
    IF (SELECT legacy_database FROM migration.ctx) IS DISTINCT FROM 'jbsNeostrata' THEN
        RAISE EXCEPTION 'legacy copy here is %, not jbsNeostrata', (SELECT legacy_database FROM migration.ctx);
    END IF;
    IF (SELECT applied FROM legacy.shift) IS DISTINCT FROM (SELECT id_offset FROM migration.ctx) THEN
        RAISE EXCEPTION 'legacy copy is not shifted by the load''s ID offset';
    END IF;
END $$;

CREATE TEMP TABLE fix_line ON COMMIT DROP AS
SELECT dep.id AS deposit_id, t.id AS tender_id, t.amount,
       row_number() OVER (PARTITION BY dep.id ORDER BY t.id) AS line_number
FROM deposits dep
JOIN legacy.tbldeposits ld ON ld.id = dep.id AND ld.void
JOIN legacy.tbldepositdetails dd ON dd.depositid = ld.id
JOIN customer_payment_tenders t ON t.id = dd.paymentdetailid AND t.mode IN ('CASH', 'CHECK')
WHERE dep.company_id = (SELECT company_id FROM migration.ctx) AND dep.origin = 'MIGRATED' AND dep.voided
  AND NOT EXISTS (SELECT 1 FROM deposit_lines l WHERE l.deposit_id = dep.id)
GROUP BY dep.id, t.id, t.amount;

INSERT INTO deposit_lines (deposit_id, tender_id, amount, line_number)
SELECT deposit_id, tender_id, amount, line_number FROM fix_line;

UPDATE deposits d SET total_amount = x.total
FROM (SELECT deposit_id, sum(amount) AS total FROM fix_line GROUP BY 1) x
WHERE d.id = x.deposit_id;

SELECT count(DISTINCT deposit_id) AS deposits_fixed, count(*) AS lines_added, coalesce(sum(amount), 0) AS amount
FROM fix_line;

COMMIT;
