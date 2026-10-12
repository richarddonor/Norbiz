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

-- A database whose tables were created by the app's ddl-auto=update (not db/init.sql) has NOT NULL
-- columns without the DEFAULTs init.sql declares, so the bulk INSERTs below that leave them out
-- (e.g. item_skus.landmark_sku) fail. Put init.sql's DEFAULTs back; metadata-only, safe to repeat.
-- Keep in step with the DEFAULTs in db/init.sql and db/migrations/001_restore_column_defaults.sql.
DO $$
DECLARE
    d record;
BEGIN
    FOR d IN
        SELECT c.table_name, c.column_name, v.dflt
        FROM (VALUES
            ('assemblies', 'created_at', 'NOW()'),
            ('assemblies', 'loaded', 'FALSE'),
            ('assemblies', 'origin', $d$'NATIVE'$d$),
            ('assemblies', 'voided', 'FALSE'),
            ('assembly_lines', 'quantity_loaded', '0'),
            ('audit_logs', 'changed_at', 'NOW()'),
            ('bill_of_material_lines', 'created_at', 'NOW()'),
            ('bill_of_material_lines', 'updated_at', 'NOW()'),
            ('bills_of_materials', 'active', 'TRUE'),
            ('bills_of_materials', 'created_at', 'NOW()'),
            ('bills_of_materials', 'updated_at', 'NOW()'),
            ('brands', 'created_at', 'NOW()'),
            ('brands', 'updated_at', 'NOW()'),
            ('companies', 'active', 'TRUE'),
            ('companies', 'created_at', 'NOW()'),
            ('companies', 'updated_at', 'NOW()'),
            ('customers', 'active', 'TRUE'),
            ('customers', 'created_at', 'NOW()'),
            ('customers', 'updated_at', 'NOW()'),
            ('delivery_receipt_lines', 'quantity_loaded', '0'),
            ('delivery_receipt_lines', 'unit_price', '0'),
            ('delivery_receipts', 'created_at', 'NOW()'),
            ('delivery_receipts', 'loaded', 'FALSE'),
            ('delivery_receipts', 'voided', 'FALSE'),
            ('document_templates', 'active', 'TRUE'),
            ('document_templates', 'created_at', 'NOW()'),
            ('document_templates', 'is_default', 'FALSE'),
            ('document_templates', 'updated_at', 'NOW()'),
            ('employees', 'active', 'TRUE'),
            ('employees', 'created_at', 'NOW()'),
            ('employees', 'updated_at', 'NOW()'),
            ('inventory_adjustment_lines', 'quantity_loaded', '0'),
            ('inventory_adjustments', 'created_at', 'NOW()'),
            ('inventory_adjustments', 'loaded', 'FALSE'),
            ('inventory_adjustments', 'voided', 'FALSE'),
            ('inventory_balances', 'quantity', '0'),
            ('inventory_balances', 'transit_quantity', '0'),
            ('inventory_balances', 'updated_at', 'NOW()'),
            ('inventory_movements', 'created_at', 'NOW()'),
            ('inventory_movements', 'transit_quantity_delta', '0'),
            ('item_categories', 'created_at', 'NOW()'),
            ('item_categories', 'updated_at', 'NOW()'),
            ('item_groups', 'active', 'TRUE'),
            ('item_groups', 'commission_rate', '0'),
            ('item_groups', 'created_at', 'NOW()'),
            ('item_groups', 'focal_commission_rate', '0'),
            ('item_groups', 'updated_at', 'NOW()'),
            ('item_prices', 'created_at', 'NOW()'),
            ('item_prices', 'updated_at', 'NOW()'),
            ('item_skus', 'active', 'TRUE'),
            ('item_skus', 'created_at', 'NOW()'),
            ('item_skus', 'landmark_sku', 'FALSE'),
            ('item_skus', 'rds_sku', 'FALSE'),
            ('item_skus', 'updated_at', 'NOW()'),
            ('items', 'active', 'TRUE'),
            ('items', 'created_at', 'NOW()'),
            ('items', 'updated_at', 'NOW()'),
            ('outlet_delivery_receipt_lines', 'quantity_loaded', '0'),
            ('outlet_delivery_receipt_lines', 'unit_price', '0'),
            ('outlet_delivery_receipts', 'created_at', 'NOW()'),
            ('outlet_delivery_receipts', 'loaded', 'FALSE'),
            ('outlet_delivery_receipts', 'origin', $d$'NATIVE'$d$),
            ('outlet_delivery_receipts', 'voided', 'FALSE'),
            ('outlet_delivery_return_lines', 'quantity_loaded', '0'),
            ('outlet_delivery_return_lines', 'unit_price', '0'),
            ('outlet_delivery_returns', 'created_at', 'NOW()'),
            ('outlet_delivery_returns', 'loaded', 'FALSE'),
            ('outlet_delivery_returns', 'voided', 'FALSE'),
            ('outlet_pull_out_lines', 'quantity_loaded', '0'),
            ('outlet_pull_out_lines', 'unit_price', '0'),
            ('outlet_pull_outs', 'created_at', 'NOW()'),
            ('outlet_pull_outs', 'loaded', 'FALSE'),
            ('outlet_pull_outs', 'origin', $d$'NATIVE'$d$),
            ('outlet_pull_outs', 'voided', 'FALSE'),
            ('outlet_receipt_lines', 'quantity_loaded', '0'),
            ('outlet_receipts', 'created_at', 'NOW()'),
            ('outlet_receipts', 'loaded', 'FALSE'),
            ('outlet_receipts', 'voided', 'FALSE'),
            ('permissions', 'created_at', 'NOW()'),
            ('permissions', 'updated_at', 'NOW()'),
            ('pull_out_reasons', 'active', 'TRUE'),
            ('pull_out_reasons', 'created_at', 'NOW()'),
            ('pull_out_reasons', 'updated_at', 'NOW()'),
            ('pull_out_receipt_lines', 'quantity_loaded', '0'),
            ('pull_out_receipts', 'created_at', 'NOW()'),
            ('pull_out_receipts', 'loaded', 'FALSE'),
            ('pull_out_receipts', 'origin', $d$'NATIVE'$d$),
            ('pull_out_receipts', 'voided', 'FALSE'),
            ('purchase_invoice_lines', 'cost_price', '0'),
            ('purchase_invoice_lines', 'discount_percentage', '0'),
            ('purchase_invoice_lines', 'quantity_loaded', '0'),
            ('purchase_invoices', 'created_at', 'NOW()'),
            ('purchase_invoices', 'discount_percentage', '0'),
            ('purchase_invoices', 'loaded', 'FALSE'),
            ('purchase_invoices', 'payment_status', $d$'UNPAID'$d$),
            ('purchase_invoices', 'voided', 'FALSE'),
            ('purchase_order_lines', 'cost_price', '0'),
            ('purchase_order_lines', 'quantity_loaded', '0'),
            ('purchase_orders', 'created_at', 'NOW()'),
            ('purchase_orders', 'loaded', 'FALSE'),
            ('purchase_orders', 'voided', 'FALSE'),
            ('purchase_receipt_lines', 'quantity_loaded', '0'),
            ('purchase_receipts', 'created_at', 'NOW()'),
            ('purchase_receipts', 'loaded', 'FALSE'),
            ('purchase_receipts', 'voided', 'FALSE'),
            ('roles', 'created_at', 'NOW()'),
            ('roles', 'updated_at', 'NOW()'),
            ('stock_transfer_lines', 'quantity_loaded', '0'),
            ('stock_transfer_lines', 'unit_price', '0'),
            ('stock_transfers', 'created_at', 'NOW()'),
            ('stock_transfers', 'loaded', 'FALSE'),
            ('stock_transfers', 'origin', $d$'NATIVE'$d$),
            ('stock_transfers', 'voided', 'FALSE'),
            ('suppliers', 'active', 'TRUE'),
            ('suppliers', 'created_at', 'NOW()'),
            ('suppliers', 'updated_at', 'NOW()'),
            ('transaction_action_definitions', 'active', 'TRUE'),
            ('transaction_action_definitions', 'created_at', 'NOW()'),
            ('transaction_action_definitions', 'sort_order', '0'),
            ('transaction_action_definitions', 'updated_at', 'NOW()'),
            ('transaction_events', 'performed_at', 'NOW()'),
            ('user_preferences', 'updated_at', 'NOW()'),
            ('users', 'created_at', 'NOW()'),
            ('users', 'updated_at', 'NOW()'),
            ('warehouses', 'active', 'TRUE'),
            ('warehouses', 'created_at', 'NOW()'),
            ('warehouses', 'main', 'FALSE'),
            ('warehouses', 'outlet', 'FALSE'),
            ('warehouses', 'updated_at', 'NOW()')
        ) v(tbl, col, dflt)
        JOIN information_schema.columns c
          ON c.table_schema = 'public' AND c.table_name = v.tbl AND c.column_name = v.col
        WHERE c.column_default IS NULL
    LOOP
        EXECUTE format('ALTER TABLE public.%I ALTER COLUMN %I SET DEFAULT %s', d.table_name, d.column_name, d.dflt);
    END LOOP;
END $$;

DROP SCHEMA IF EXISTS migration CASCADE;
CREATE SCHEMA migration;
