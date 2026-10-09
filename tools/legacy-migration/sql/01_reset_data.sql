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

-- SKUs gained their own company (item_id became optional) and the legacy price point columns; an app
-- that hasn't restarted on the new code yet hasn't added them. The table is empty here, so company_id
-- can be NOT NULL straight away. Mirrors db/init.sql.
ALTER TABLE item_skus ADD COLUMN IF NOT EXISTS company_id       BIGINT NOT NULL;
ALTER TABLE item_skus ADD COLUMN IF NOT EXISTS item_category_id BIGINT;
ALTER TABLE item_skus ADD COLUMN IF NOT EXISTS brand_id         BIGINT;
ALTER TABLE item_skus ADD COLUMN IF NOT EXISTS price_type       VARCHAR(20);
ALTER TABLE item_skus ADD COLUMN IF NOT EXISTS store_item_code  VARCHAR(100);
ALTER TABLE item_skus ADD COLUMN IF NOT EXISTS barcode          VARCHAR(100);
ALTER TABLE item_skus ADD COLUMN IF NOT EXISTS vendor_part      VARCHAR(100);
ALTER TABLE item_skus ADD COLUMN IF NOT EXISTS rds_description  VARCHAR(255);
ALTER TABLE item_skus ADD COLUMN IF NOT EXISTS active           BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE item_skus ADD COLUMN IF NOT EXISTS rds_sku          BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE item_skus ADD COLUMN IF NOT EXISTS landmark_sku     BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE item_skus ALTER COLUMN item_id DROP NOT NULL;
-- The brand/category/price search index is rebuilt by 10_master.sql after the SKU bulk load.
DROP INDEX IF EXISTS item_skus_brand_category_price_ix;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'item_skus_company_id_fk') THEN
        ALTER TABLE item_skus ADD CONSTRAINT ITEM_SKUS_COMPANY_ID_FK FOREIGN KEY (company_id) REFERENCES companies(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'item_skus_item_category_id_fk') THEN
        ALTER TABLE item_skus ADD CONSTRAINT ITEM_SKUS_ITEM_CATEGORY_ID_FK FOREIGN KEY (item_category_id) REFERENCES item_categories(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'item_skus_brand_id_fk') THEN
        ALTER TABLE item_skus ADD CONSTRAINT ITEM_SKUS_BRAND_ID_FK FOREIGN KEY (brand_id) REFERENCES brands(id);
    END IF;
END $$;

DROP SCHEMA IF EXISTS migration CASCADE;
CREATE SCHEMA migration;
