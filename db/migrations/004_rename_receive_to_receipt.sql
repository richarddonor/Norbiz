-- Renames the "Receive" transactions to "Receipt" ("Receive" is a verb, not a document name):
-- Purchase Receive -> Purchase Receipt, Outlet Receive -> Outlet Receipt,
-- Pull Out Receive -> Pull-out Receipt. Also renames the dashboard widgets
-- PENDING_OUTLET_RECEIVES -> PENDING_OUTLET_RECEIPTS and
-- PULL_OUTS_AWAITING_RECEIVE -> PULL_OUTS_AWAITING_RECEIPT.
--
-- Covers the tables/columns/constraints/indexes/sequences, every column that stores the transaction
-- type or entity name as a string, permission names (role assignments are kept: rows are renamed, not
-- recreated), and users' saved preferences (dashboard layout, column layouts, page sizes).
-- Reference numbers and their prefixes (PR/OR/OPO) are unchanged.
--
-- Idempotent: every step matches only the old names, which a database built from the current
-- init.sql never has.

-- ---- schema --------------------------------------------------------------------------------------

DO $$
DECLARE
    r record;
BEGIN
    -- Tables: purchase_receives -> purchase_receipts, purchase_receive_lines -> purchase_receipt_lines, ...
    FOR r IN SELECT c.relname FROM pg_class c
             WHERE c.relnamespace = current_schema()::regnamespace AND c.relkind = 'r'
               AND c.relname ~ '^(purchase|outlet|pull_out)_receive(s|_lines)$'
    LOOP
        EXECUTE format('ALTER TABLE %I RENAME TO %I', r.relname,
                       regexp_replace(r.relname, '_receive(s?)', '_receipt\1'));
    END LOOP;

    -- FK columns on the line tables: purchase_receive_id -> purchase_receipt_id, ...
    FOR r IN SELECT table_name, column_name FROM information_schema.columns
             WHERE table_schema = current_schema()
               AND column_name ~ '^(purchase|outlet|pull_out)_receive_id$'
    LOOP
        EXECUTE format('ALTER TABLE %I RENAME COLUMN %I TO %I', r.table_name, r.column_name,
                       replace(r.column_name, '_receive_id', '_receipt_id'));
    END LOOP;

    -- Constraints (renaming a PK/unique constraint renames its index too). init.sql's names are
    -- unquoted, so they are stored lower-case; the new names are lower-cased the same way.
    FOR r IN SELECT con.conname, con.conrelid::regclass AS tbl FROM pg_constraint con
             JOIN pg_class c ON c.oid = con.conrelid
             WHERE c.relnamespace = current_schema()::regnamespace
               AND con.conname ~* '(purchase|outlet|pull_out)_receive(s?)_'
    LOOP
        EXECUTE format('ALTER TABLE %s RENAME CONSTRAINT %I TO %I', r.tbl, r.conname,
                       regexp_replace(lower(r.conname), '(purchase|outlet|pull_out)_receive(s?)_', '\1_receipt\2_', 'g'));
    END LOOP;

    -- Remaining indexes and sequences.
    FOR r IN SELECT c.relname, c.relkind FROM pg_class c
             WHERE c.relnamespace = current_schema()::regnamespace AND c.relkind IN ('i', 'S')
               AND c.relname ~* '(purchase|outlet|pull_out)_receive(s?)_'
    LOOP
        EXECUTE format(CASE r.relkind WHEN 'i' THEN 'ALTER INDEX %I RENAME TO %I' ELSE 'ALTER SEQUENCE %I RENAME TO %I' END,
                       r.relname,
                       regexp_replace(lower(r.relname), '(purchase|outlet|pull_out)_receive(s?)_', '\1_receipt\2_', 'g'));
    END LOOP;
END $$;

-- ---- permissions ---------------------------------------------------------------------------------

UPDATE permissions p SET name = v.new_name
FROM (VALUES ('VIEW_DASHBOARD_PENDING_OUTLET_RECEIVES',    'VIEW_DASHBOARD_PENDING_OUTLET_RECEIPTS'),
             ('VIEW_DASHBOARD_PULL_OUTS_AWAITING_RECEIVE', 'VIEW_DASHBOARD_PULL_OUTS_AWAITING_RECEIPT')) v(old_name, new_name)
WHERE p.name = v.old_name
  AND NOT EXISTS (SELECT 1 FROM permissions x WHERE x.name = v.new_name);

-- VIEW_PURCHASE_RECEIVE -> VIEW_PURCHASE_RECEIPT, VIEW_OUTLET_RECEIVE_DETAILED_REPORT -> VIEW_OUTLET_RECEIPT_DETAILED_REPORT, ...
UPDATE permissions p
SET name = regexp_replace(p.name, '(PURCHASE|OUTLET|PULL_OUT)_RECEIVE(_|$)', '\1_RECEIPT\2')
WHERE p.name ~ '(PURCHASE|OUTLET|PULL_OUT)_RECEIVE(_|$)'
  AND NOT EXISTS (SELECT 1 FROM permissions x
                  WHERE x.name = regexp_replace(p.name, '(PURCHASE|OUTLET|PULL_OUT)_RECEIVE(_|$)', '\1_RECEIPT\2'));

-- ---- stored transaction types / entity names -----------------------------------------------------

-- PURCHASE_RECEIVE -> PURCHASE_RECEIPT, PULL_OUT_RECEIVE_VOID -> PULL_OUT_RECEIPT_VOID, ...
UPDATE inventory_movements
SET source_type = regexp_replace(source_type, '^(PURCHASE|OUTLET|PULL_OUT)_RECEIVE(_|$)', '\1_RECEIPT\2')
WHERE source_type ~ '^(PURCHASE|OUTLET|PULL_OUT)_RECEIVE(_|$)';

UPDATE transaction_sequences
SET transaction_type = regexp_replace(transaction_type, '^(PURCHASE|OUTLET|PULL_OUT)_RECEIVE$', '\1_RECEIPT')
WHERE transaction_type ~ '^(PURCHASE|OUTLET|PULL_OUT)_RECEIVE$';

UPDATE transaction_events
SET transaction_type = regexp_replace(transaction_type, '^(PURCHASE|OUTLET|PULL_OUT)_RECEIVE$', '\1_RECEIPT')
WHERE transaction_type ~ '^(PURCHASE|OUTLET|PULL_OUT)_RECEIVE$';

UPDATE transaction_action_definitions
SET transaction_type = regexp_replace(transaction_type, '^(PURCHASE|OUTLET|PULL_OUT)_RECEIVE$', '\1_RECEIPT')
WHERE transaction_type ~ '^(PURCHASE|OUTLET|PULL_OUT)_RECEIVE$';

UPDATE document_templates
SET document_type = regexp_replace(document_type, '^(PURCHASE|OUTLET|PULL_OUT)_RECEIVE$', '\1_RECEIPT')
WHERE document_type ~ '^(PURCHASE|OUTLET|PULL_OUT)_RECEIVE$';

-- Audit entity names are the entity's simple class name (PurchaseReceive -> PurchaseReceipt, ...).
UPDATE audit_logs
SET entity_type = regexp_replace(entity_type, '^(Purchase|Outlet|PullOut)Receive(Line)?$', '\1Receipt\2')
WHERE entity_type ~ '^(Purchase|Outlet|PullOut)Receive(Line)?$';

UPDATE audit_logs
SET parent_type = regexp_replace(parent_type, '^(Purchase|Outlet|PullOut)Receive$', '\1Receipt')
WHERE parent_type ~ '^(Purchase|Outlet|PullOut)Receive$';

-- ---- user preferences ----------------------------------------------------------------------------

-- Dashboard layout: widget keys inside the JSON value.
UPDATE user_preferences
SET value = replace(replace(value, '"pending-outlet-receives"', '"pending-outlet-receipts"'),
                    '"pull-outs-awaiting-receive"', '"pull-outs-awaiting-receipt"'),
    updated_at = NOW()
WHERE pref_key = 'dashboard.layout'
  AND (value LIKE '%"pending-outlet-receives"%' OR value LIKE '%"pull-outs-awaiting-receive"%');

-- Column layouts / page sizes keyed by list or endpoint, e.g. columns:purchase-receives,
-- list.pageSize:purchase-receives, ...:outlet-receive-detailed. Skips a user that already saved the new key.
UPDATE user_preferences up
SET pref_key = regexp_replace(up.pref_key, '(purchase|outlet|pull-out)-receive(s?)\y', '\1-receipt\2', 'g')
WHERE up.pref_key ~ '(purchase|outlet|pull-out)-receive(s?)\y'
  AND NOT EXISTS (SELECT 1 FROM user_preferences x
                  WHERE x.user_id = up.user_id
                    AND x.pref_key = regexp_replace(up.pref_key, '(purchase|outlet|pull-out)-receive(s?)\y', '\1-receipt\2', 'g'));
