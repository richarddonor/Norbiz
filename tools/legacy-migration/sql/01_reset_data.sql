-- ============================================================================================
-- 01 — Wipe Norbiz down to its seeded basics so the migration loads into an empty database.
--
-- Kept: permissions, roles, role_permissions, and the users DataInitializer seeds (admin,
-- super_admin, system_admin) with their roles and preferences. Everything else — companies,
-- master data, transactions, ledger, history, templates, audit log — is truncated with its
-- identity sequences restarted. DataInitializer recreates the default company and print templates
-- on the next app start.
--
-- Destructive: run_migration.sh only runs this after its safety checks and a pg_dump backup.
-- ============================================================================================

DO $$
DECLARE
    keep CONSTANT text[] := ARRAY['permissions', 'roles', 'role_permissions', 'users', 'user_roles', 'user_preferences'];
    tables text;
BEGIN
    SELECT string_agg(format('public.%I', tablename), ', ')
    INTO tables
    FROM pg_tables
    WHERE schemaname = 'public' AND tablename <> ALL (keep);

    IF tables IS NOT NULL THEN
        EXECUTE 'TRUNCATE ' || tables || ' RESTART IDENTITY CASCADE';
    END IF;
END $$;

DELETE FROM user_preferences WHERE user_id IN (SELECT id FROM users WHERE username NOT IN ('admin', 'super_admin', 'system_admin'));
DELETE FROM user_roles       WHERE user_id IN (SELECT id FROM users WHERE username NOT IN ('admin', 'super_admin', 'system_admin'));
DELETE FROM users            WHERE username NOT IN ('admin', 'super_admin', 'system_admin');
SELECT setval(pg_get_serial_sequence('users', 'id'), (SELECT coalesce(max(id), 0) + 1 FROM users), false);

-- Schema housekeeping the app's ddl-auto=update can't do: SKU codes became unique per item only, but
-- a database created before that change still carries the old global constraint (see db/init.sql).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'item_skus_sku_code_uq') THEN
        ALTER TABLE item_skus DROP CONSTRAINT item_skus_sku_code_uq;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'item_skus_item_sku_code_uq') THEN
        ALTER TABLE item_skus ADD CONSTRAINT ITEM_SKUS_ITEM_SKU_CODE_UQ UNIQUE (item_id, sku_code);
    END IF;
END $$;

DROP SCHEMA IF EXISTS migration CASCADE;
CREATE SCHEMA migration;
