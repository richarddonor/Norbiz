-- ============================================================================================
-- 01 — Make room for the legacy company being loaded, and only for it.
--
-- One Norbiz database holds several migrated companies (one per legacy database: jbsKarutora,
-- jbsNeostrata, ...). The company a legacy database loads into is the Norbiz company named like its
-- tblCompany row; it is created on the first run. A re-run wipes that company's data — master
-- data, transactions, ledger, AR/AP, history, its audit trail, the users that only it had — and
-- nothing of any other company. Kept: the company row itself (name, theme, logo), its print
-- templates, accounting settings someone set by hand, and every user that is still in use elsewhere.
--
-- Identity: the loader keeps legacy IDs as Norbiz IDs. A second company's legacy IDs overlap the
-- first's, so every ID column of the legacy copy is shifted by migration.ctx.id_offset first: 0 when
-- nothing else is loaded (a lone Karutora run is unchanged), else a round number above every ID
-- in use, so the migrated rows can never collide with another company's (or a future native) row.
--
-- Destructive: run_migration.sh only runs this after its safety checks and a pg_dump backup.
-- ============================================================================================

DROP SCHEMA IF EXISTS migration CASCADE;
CREATE SCHEMA migration;

-- Run context: the legacy database, the company every migrated row belongs to, the cutover
-- instant, the actor recorded on rows that have no legacy author, and the ID offset.
CREATE TABLE migration.ctx (
    legacy_database text NOT NULL, company_id bigint, company_name text NOT NULL, cutover timestamptz NOT NULL,
    actor text NOT NULL, id_offset bigint NOT NULL DEFAULT 0, reloaded boolean NOT NULL DEFAULT false);
INSERT INTO migration.ctx (legacy_database, company_name, cutover, actor)
SELECT (SELECT database FROM legacy.source),
       coalesce((SELECT left(btrim(name), 255) FROM legacy.tblcompany WHERE btrim(name) <> '' ORDER BY id LIMIT 1), 'Legacy Company'),
       now(), 'legacy-migration';

-- Anything the loader had to fix or couldn't map — listed by report.py. legacy_id is the ID as in the
-- (shifted) legacy copy; the report prints it without the offset.
CREATE TABLE migration.issues (kind text NOT NULL, entity text NOT NULL, legacy_id bigint, detail text);

UPDATE migration.ctx SET company_id = c.id, reloaded = true
FROM (SELECT id FROM companies WHERE name = (SELECT company_name FROM migration.ctx) ORDER BY id LIMIT 1) c;

-- ---- wipe the company's previous load -------------------------------------------------------

-- Auditable entity -> its table (company-scoped ones; their child entities are found through
-- audit_logs.parent_type/parent_id).
CREATE TABLE migration.audited (entity_type text, table_name text);
INSERT INTO migration.audited VALUES
    ('AccountingSettings', 'accounting_settings'), ('Bank', 'banks'), ('BankAccount', 'bank_accounts'),
    ('BillOfMaterial', 'bills_of_materials'), ('Brand', 'brands'), ('Customer', 'customers'), ('Employee', 'employees'),
    ('Item', 'items'), ('ItemCategory', 'item_categories'), ('ItemGroup', 'item_groups'), ('ItemSku', 'item_skus'),
    ('PullOutReason', 'pull_out_reasons'), ('Supplier', 'suppliers'), ('TransactionActionDefinition', 'transaction_action_definitions'),
    ('Warehouse', 'warehouses');

DO $$
DECLARE
    cid CONSTANT bigint := (SELECT company_id FROM migration.ctx);
    -- Company configuration a re-run keeps (the company row itself is never deleted).
    keep CONSTANT text[] := ARRAY['companies', 'document_templates', 'accounting_settings', 'user_companies'];
    t record;
    n bigint;
    total bigint;
BEGIN
    IF cid IS NULL THEN
        RAISE NOTICE 'First load of %: a new company will be created', (SELECT company_name FROM migration.ctx);
        RETURN;
    END IF;
    RAISE NOTICE 'Re-load of % (company %): wiping its previous data', (SELECT company_name FROM migration.ctx), cid;

    -- FK triggers off for the wipe: rows are deleted company table by company table, then every
    -- child row left pointing at a deleted parent is swept up (works for any table added later).
    SET LOCAL session_replication_role = replica;

    -- Its audit trail: logs of the company's entities and of their children.
    FOR t IN SELECT a.* FROM migration.audited a JOIN information_schema.tables x ON x.table_schema = 'public' AND x.table_name = a.table_name LOOP
        EXECUTE format('DELETE FROM audit_logs l USING public.%I r WHERE r.company_id = $1 AND '
                       '((l.entity_type = $2 AND l.entity_id = r.id) OR (l.parent_type = $2 AND l.parent_id = r.id))', t.table_name)
        USING cid, t.entity_type;
    END LOOP;
    DELETE FROM audit_logs WHERE entity_type = 'Company' AND entity_id = cid AND changes LIKE '{"migration"%';

    -- Users only this company had, created by a previous load (natively created users stay, and
    -- keep their membership).
    CREATE TEMP TABLE gone_users ON COMMIT DROP AS
    SELECT u.id FROM users u
    WHERE u.created_by = 'legacy-migration' AND u.username NOT IN ('admin', 'super_admin', 'system_admin')
      AND EXISTS (SELECT 1 FROM user_companies c WHERE c.user_id = u.id AND c.company_id = cid)
      AND NOT EXISTS (SELECT 1 FROM user_companies c WHERE c.user_id = u.id AND c.company_id <> cid);
    DELETE FROM audit_logs WHERE entity_type = 'User' AND entity_id IN (SELECT id FROM gone_users);
    DELETE FROM user_preferences WHERE user_id IN (SELECT id FROM gone_users);
    DELETE FROM user_roles WHERE user_id IN (SELECT id FROM gone_users);
    DELETE FROM user_companies WHERE user_id IN (SELECT id FROM gone_users);
    -- Memberships a previous load gave to users of other companies (re-linked by 10_master.sql).
    DELETE FROM user_companies c USING users u
    WHERE c.user_id = u.id AND c.company_id = cid AND u.created_by = 'legacy-migration'
      AND u.username NOT IN ('admin', 'super_admin', 'system_admin');
    DELETE FROM users WHERE id IN (SELECT id FROM gone_users);

    -- Accounting settings stay only when someone set them in the app.
    DELETE FROM accounting_settings WHERE company_id = cid AND created_by = 'legacy-migration';

    FOR t IN SELECT c.table_name FROM information_schema.columns c
             JOIN information_schema.tables x ON x.table_schema = c.table_schema AND x.table_name = c.table_name AND x.table_type = 'BASE TABLE'
             WHERE c.table_schema = 'public' AND c.column_name = 'company_id' AND c.table_name <> ALL (keep) LOOP
        EXECUTE format('DELETE FROM public.%I WHERE company_id = $1', t.table_name) USING cid;
        GET DIAGNOSTICS n = ROW_COUNT;
        IF n > 0 THEN RAISE NOTICE '  % : % row(s)', t.table_name, n; END IF;
    END LOOP;

    -- Sweep child rows whose parent is gone, until nothing changes. Only single-column FKs into
    -- tables other than companies / users / roles / permissions (those parents are never deleted here).
    LOOP
        total := 0;
        FOR t IN SELECT cl.relname AS child, a.attname AS col, pl.relname AS parent, pa.attname AS pcol
                 FROM pg_constraint k
                 JOIN pg_class cl ON cl.oid = k.conrelid JOIN pg_namespace ns ON ns.oid = cl.relnamespace AND ns.nspname = 'public'
                 JOIN pg_class pl ON pl.oid = k.confrelid
                 JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = k.conkey[1]
                 JOIN pg_attribute pa ON pa.attrelid = k.confrelid AND pa.attnum = k.confkey[1]
                 WHERE k.contype = 'f' AND array_length(k.conkey, 1) = 1
                   AND pl.relname NOT IN ('companies', 'users', 'roles', 'permissions') LOOP
            EXECUTE format('DELETE FROM public.%I c WHERE c.%I IS NOT NULL AND NOT EXISTS (SELECT 1 FROM public.%I p WHERE p.%I = c.%I)',
                           t.child, t.col, t.parent, t.pcol, t.col);
            GET DIAGNOSTICS n = ROW_COUNT;
            IF n > 0 THEN RAISE NOTICE '  % (orphans of %) : % row(s)', t.child, t.parent, n; END IF;
            total := total + n;
        END LOOP;
        EXIT WHEN total = 0;
    END LOOP;
END $$;
RESET session_replication_role;

-- ---- ID offset ------------------------------------------------------------------------------
-- 0 while no table the loader writes explicit IDs into holds a row (an empty database: legacy IDs
-- are kept as they are). Otherwise the next multiple of 1,000,000 above the highest ID of any table.
DO $$
DECLARE
    t record;
    m bigint;
    top bigint := 0;
    used boolean := false;
BEGIN
    FOR t IN SELECT c.relname FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = 'public' AND c.relkind = 'r' AND a.attname = 'id' AND a.attidentity IN ('a', 'd') LOOP
        EXECUTE format('SELECT max(id) FROM public.%I', t.relname) INTO m;
        top := greatest(top, coalesce(m, 0));
        IF m IS NOT NULL AND t.relname IN ('items', 'employees', 'customers', 'suppliers', 'warehouses', 'inventory_adjustments',
                                           'delivery_receipts', 'outlet_delivery_receipts', 'sales_invoices', 'sales_invoice_returns', 'customer_payments',
                                           'purchase_invoices', 'pull_out_reasons', 'bills_of_materials', 'banks', 'bank_accounts') THEN
            used := true;
        END IF;
    END LOOP;
    UPDATE migration.ctx SET id_offset = CASE WHEN used THEN (top / 1000000 + 1) * 1000000 ELSE 0 END;
END $$;

-- Shift every ID column of the legacy copy by the offset: each table's own id and every column
-- that references a migrated row (items, employees, customers, warehouses, suppliers, documents,
-- lines, banks). Lookup tables whose rows get fresh Norbiz IDs (brands, groups, categories, types,
-- terms, modes of payment) and lookup references (typeid, unitid, termsid, ...) are left alone.
-- legacy.shift remembers the shift already applied, so --skip-extract re-runs adjust by the difference.
CREATE TABLE IF NOT EXISTS legacy.shift (applied bigint NOT NULL);
INSERT INTO legacy.shift SELECT 0 WHERE NOT EXISTS (SELECT 1 FROM legacy.shift);

DO $$
DECLARE
    delta CONSTANT bigint := (SELECT id_offset FROM migration.ctx) - (SELECT applied FROM legacy.shift);
    unshifted_ids CONSTANT text[] := ARRAY['tblbrands', 'tblcategorychartsorig', 'tblcompany', 'tblcustomertypes', 'tblitemdescriptions',
        'tblmodesofpayment', 'tblsitransactiontypes', 'tblterms', 'tbltransactiontypes', 'tblcustomercreditmemotypes', 'tblsmskus'];
    refs CONSTANT text[] := ARRAY['agent1', 'agent2', 'agent3', 'agentid', 'ancdetailid', 'ancid', 'ancmasterid', 'approvedby', 'approvedbyid',
        'approvedid', 'bankid', 'bomid', 'checkedby', 'checkedbyid', 'checkedid', 'createdbyid', 'creditmemoid', 'custodianid',
        'customerbranchid', 'customercmmasterid', 'customerid', 'customerpaymentdetailid', 'customerpaymentid', 'debitmemoid',
        'deliveredbyid', 'depositid', 'deriveddetailid', 'detailid', 'drid', 'employeeid', 'encodedby', 'encodedbyid', 'invoiceid',
        'issuedtoid', 'itemid', 'maindetailid', 'masterid', 'modifiedbyid', 'notedby', 'notedbyid', 'odrid', 'payableid',
        'paymentdetailid', 'poid', 'preparedby', 'preparedbyid', 'pulloutreasonid', 'receivebyid', 'receivedby', 'receivedbyid',
        'receiveid', 'returnid', 'returnslipmasterid', 'rootparentid', 'salesinvoiceid', 'sidetailid', 'simasterid', 'supplierid',
        'supplierinvoiceid', 'supplierpaymentdetailid', 'supplierpaymentid', 'transferredby', 'uicustomerreturnid', 'userid',
        'voidedby', 'voidedbyid', 'warehouseid'];
    t record;
    sets text;
BEGIN
    IF delta = 0 THEN RETURN; END IF;
    FOR t IN SELECT c.table_name, string_agg(c.column_name, ',') AS cols
             FROM information_schema.columns c
             WHERE c.table_schema = 'legacy' AND c.data_type IN ('integer', 'bigint', 'smallint')
               AND ((c.column_name = 'id' AND c.table_name <> ALL (unshifted_ids))
                    OR (c.column_name = ANY (refs) AND NOT (c.table_name = 'tblcategorychartsorig' AND c.column_name = 'parentid')))
             GROUP BY c.table_name LOOP
        SELECT string_agg(format('%I = %I + %s', col, col, delta), ', ') INTO sets FROM unnest(string_to_array(t.cols, ',')) col;
        EXECUTE format('UPDATE legacy.%I SET %s', t.table_name, sets);
    END LOOP;
    UPDATE legacy.shift SET applied = applied + delta;
    ANALYZE;
END $$;

-- The legacy main warehouse (tblWareHouses ID 1) as a Norbiz warehouse ID.
CREATE FUNCTION migration.main_wh() RETURNS bigint LANGUAGE sql STABLE AS $$ SELECT 1 + id_offset FROM migration.ctx $$;
-- A Norbiz ID back to its legacy ID (for the report).
CREATE FUNCTION migration.legacy_id(id bigint) RETURNS bigint LANGUAGE sql STABLE AS $$ SELECT $1 - id_offset FROM migration.ctx $$;

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
            ('sales_invoice_lines', 'quantity_loaded', '0'),
            ('sales_invoice_return_lines', 'posts_stock', 'TRUE'),
            ('sales_invoice_return_lines', 'unit_price', '0'),
            ('sales_invoice_returns', 'created_at', 'NOW()'),
            ('sales_invoice_returns', 'origin', $d$'NATIVE'$d$),
            ('sales_invoice_returns', 'voided', 'FALSE'),
            ('sales_invoices', 'loaded', 'FALSE'),
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


CREATE FUNCTION migration.cid() RETURNS bigint LANGUAGE sql STABLE AS $$ SELECT company_id FROM migration.ctx $$;
