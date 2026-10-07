-- ============================================================
-- Norbiz — drop all user-created objects
-- WARNING: This is destructive and irreversible.
-- Intended for local dev resets only — never run against production.
-- Usage: psql -U admin -d norbiz -f db/drop_all.sql
-- ============================================================

-- Drop tables in reverse dependency order.
-- CASCADE is included as a safety net for any undeclared dependencies.

DROP TABLE IF EXISTS user_preferences                            CASCADE;
DROP TABLE IF EXISTS assembly_lines                              CASCADE;
DROP TABLE IF EXISTS assemblies                                  CASCADE;
DROP TABLE IF EXISTS pull_out_receive_lines                      CASCADE;
DROP TABLE IF EXISTS pull_out_receives                           CASCADE;
DROP TABLE IF EXISTS outlet_pull_out_lines                       CASCADE;
DROP TABLE IF EXISTS outlet_pull_outs                            CASCADE;
DROP TABLE IF EXISTS stock_transfer_lines                        CASCADE;
DROP TABLE IF EXISTS outlet_delivery_return_lines                CASCADE;
DROP TABLE IF EXISTS outlet_delivery_returns                     CASCADE;
DROP TABLE IF EXISTS outlet_delivery_receipt_lines               CASCADE;
DROP TABLE IF EXISTS outlet_delivery_receipts                    CASCADE;
DROP TABLE IF EXISTS outlet_receive_lines                        CASCADE;
DROP TABLE IF EXISTS outlet_receives                             CASCADE;
DROP TABLE IF EXISTS delivery_receipt_lines                      CASCADE;
DROP TABLE IF EXISTS delivery_receipts                           CASCADE;
DROP TABLE IF EXISTS stock_transfers                             CASCADE;
DROP TABLE IF EXISTS bill_of_material_lines                      CASCADE;
DROP TABLE IF EXISTS bills_of_materials                          CASCADE;
DROP TABLE IF EXISTS pull_out_reasons                            CASCADE;
DROP TABLE IF EXISTS transaction_events                          CASCADE;
DROP TABLE IF EXISTS transaction_action_definition_roles         CASCADE;
DROP TABLE IF EXISTS transaction_action_definition_prerequisites CASCADE;
DROP TABLE IF EXISTS transaction_action_definitions              CASCADE;
DROP TABLE IF EXISTS audit_logs      CASCADE;
DROP TABLE IF EXISTS brands          CASCADE;
DROP TABLE IF EXISTS item_prices     CASCADE;
DROP TABLE IF EXISTS item_skus       CASCADE;
DROP TABLE IF EXISTS items           CASCADE;
DROP TABLE IF EXISTS item_categories CASCADE;
DROP TABLE IF EXISTS item_groups     CASCADE;
DROP TABLE IF EXISTS user_companies  CASCADE;
DROP TABLE IF EXISTS user_roles      CASCADE;
DROP TABLE IF EXISTS role_permissions CASCADE;
DROP TABLE IF EXISTS companies       CASCADE;
DROP TABLE IF EXISTS users           CASCADE;
DROP TABLE IF EXISTS roles           CASCADE;
DROP TABLE IF EXISTS permissions     CASCADE;

-- Views
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN SELECT table_name FROM information_schema.views
             WHERE table_schema = 'public'
    LOOP
        EXECUTE 'DROP VIEW IF EXISTS public.' || quote_ident(r.table_name) || ' CASCADE';
    END LOOP;
END $$;

-- Functions and procedures (excludes extension-owned objects)
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT p.proname AS routine_name,
               CASE p.prokind WHEN 'p' THEN 'PROCEDURE' ELSE 'FUNCTION' END AS routine_type
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public'
          AND p.prokind IN ('f', 'p')
          AND NOT EXISTS (
              SELECT 1 FROM pg_depend d
              WHERE d.objid = p.oid AND d.deptype = 'e'
          )
    LOOP
        EXECUTE 'DROP ' || r.routine_type || ' IF EXISTS public.' || quote_ident(r.routine_name) || ' CASCADE';
    END LOOP;
END $$;

-- Sequences not attached to identity columns
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN SELECT sequence_name FROM information_schema.sequences
             WHERE sequence_schema = 'public'
    LOOP
        EXECUTE 'DROP SEQUENCE IF EXISTS public.' || quote_ident(r.sequence_name) || ' CASCADE';
    END LOOP;
END $$;

DROP EXTENSION IF EXISTS "uuid-ossp";
