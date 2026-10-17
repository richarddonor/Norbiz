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
--
-- Also safe when the new version already started once against this database before the migration
-- (Hibernate ddl-auto then created empty new-name tables, DataInitializer the new permission codes and
-- DefaultDocumentTemplateProvisioner new-type default templates): empty new-name tables are dropped,
-- duplicate permissions are merged (role assignments kept), and the auto-provisioned templates are
-- dropped in favour of the company's existing ones. A new-name table that already holds rows stops the
-- migration — those transactions would need merging by hand.

-- ---- schema --------------------------------------------------------------------------------------

DO $$
DECLARE
    r     record;
    found boolean;
BEGIN
    -- New-name tables already created by Hibernate (app started before this migration): drop them if
    -- empty, so the rename below can take their place.
    FOR r IN SELECT c.relname AS old_name, regexp_replace(c.relname, '_receive(s?)', '_receipt\1') AS new_name
             FROM pg_class c
             WHERE c.relnamespace = current_schema()::regnamespace AND c.relkind = 'r'
               AND c.relname ~ '^(purchase|outlet|pull_out)_receive(s|_lines)$'
               AND to_regclass(regexp_replace(c.relname, '_receive(s?)', '_receipt\1')) IS NOT NULL
    LOOP
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I)', r.new_name) INTO found;
        IF found THEN
            RAISE EXCEPTION 'Both % and % exist and % has rows; merge them by hand before renaming',
                r.old_name, r.new_name, r.new_name;
        END IF;
        EXECUTE format('DROP TABLE %I CASCADE', r.new_name);
    END LOOP;

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

CREATE FUNCTION pg_temp.receipt_permission(p_name text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE p_name
        WHEN 'VIEW_DASHBOARD_PENDING_OUTLET_RECEIVES'    THEN 'VIEW_DASHBOARD_PENDING_OUTLET_RECEIPTS'
        WHEN 'VIEW_DASHBOARD_PULL_OUTS_AWAITING_RECEIVE' THEN 'VIEW_DASHBOARD_PULL_OUTS_AWAITING_RECEIPT'
        -- VIEW_PURCHASE_RECEIVE -> VIEW_PURCHASE_RECEIPT, VIEW_OUTLET_RECEIVE_DETAILED_REPORT -> VIEW_OUTLET_RECEIPT_DETAILED_REPORT, ...
        ELSE regexp_replace(p_name, '(PURCHASE|OUTLET|PULL_OUT)_RECEIVE(_|$)', '\1_RECEIPT\2')
    END
$$;

-- Both codes exist (DataInitializer already seeded the new one): give the new code every role that had
-- the old one, then drop the old one (its role_permissions rows cascade).
INSERT INTO role_permissions (role_id, permission_id)
SELECT rp.role_id, n.id
FROM permissions o
JOIN permissions n ON n.name = pg_temp.receipt_permission(o.name) AND n.id <> o.id
JOIN role_permissions rp ON rp.permission_id = o.id
ON CONFLICT DO NOTHING;

DELETE FROM permissions o
USING permissions n
WHERE n.name = pg_temp.receipt_permission(o.name) AND n.id <> o.id;

-- Only the old code exists: rename it in place (role assignments follow the row).
UPDATE permissions SET name = pg_temp.receipt_permission(name)
WHERE pg_temp.receipt_permission(name) <> name;

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

-- Default templates DefaultDocumentTemplateProvisioner created for the new type names: drop them where
-- the company already has templates under the old name (which may have been customised).
DELETE FROM document_templates n
WHERE n.document_type ~ '^(PURCHASE|OUTLET|PULL_OUT)_RECEIPT$'
  AND EXISTS (SELECT 1 FROM document_templates o
              WHERE o.company_id = n.company_id
                AND o.document_type = regexp_replace(n.document_type, '_RECEIPT$', '_RECEIVE'));

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
