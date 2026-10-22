-- Removes Customer/Supplier Credit Applications (CCA/SCA). Existing credits — advances, supplier debit memos,
-- customer credit memos — are now used through Customer/Supplier Payments (`credits`), which may consist of
-- credits alone (docs/ACCOUNTING.md).
--
-- Refuses to run if a credit application was ever posted (or left anything behind in the ledger or history), so no
-- record is dropped silently: void and re-post those as payments first, or keep the previous release.
--
-- Idempotent: on a fresh database (init.sql no longer creates the tables) or a re-run, every step is a no-op.

DO $$
BEGIN
    IF (to_regclass('customer_credit_applications') IS NOT NULL AND EXISTS (SELECT 1 FROM customer_credit_applications))
        OR (to_regclass('supplier_credit_applications') IS NOT NULL AND EXISTS (SELECT 1 FROM supplier_credit_applications))
        OR EXISTS (SELECT 1 FROM open_item_allocations
                   WHERE source_type IN ('CUSTOMER_CREDIT_APPLICATION', 'SUPPLIER_CREDIT_APPLICATION'))
        OR EXISTS (SELECT 1 FROM transaction_events
                   WHERE transaction_type IN ('CUSTOMER_CREDIT_APPLICATION', 'SUPPLIER_CREDIT_APPLICATION')) THEN
        RAISE EXCEPTION 'Credit applications exist (customer_credit_applications / supplier_credit_applications, or their '
            'allocations/history). This release removes the module; migrate those records to payments before deploying.';
    END IF;
END $$;

DROP TABLE IF EXISTS customer_credit_application_lines;
DROP TABLE IF EXISTS customer_credit_applications;
DROP TABLE IF EXISTS supplier_credit_application_lines;
DROP TABLE IF EXISTS supplier_credit_applications;

-- Per-company configuration and numbering of the removed transaction types.
DELETE FROM document_templates
WHERE document_type IN ('CUSTOMER_CREDIT_APPLICATION', 'SUPPLIER_CREDIT_APPLICATION');

-- Prerequisites/roles of these definitions cascade. A prerequisite pointing at one from a definition that stays
-- can't exist: prerequisites are always of the same transaction type.
DELETE FROM transaction_action_definitions
WHERE transaction_type IN ('CUSTOMER_CREDIT_APPLICATION', 'SUPPLIER_CREDIT_APPLICATION');

DELETE FROM transaction_sequences
WHERE transaction_type IN ('CUSTOMER_CREDIT_APPLICATION', 'SUPPLIER_CREDIT_APPLICATION');

-- Their permissions (role assignments cascade).
DELETE FROM permissions
WHERE name IN ('VIEW_CUSTOMER_CREDIT_APPLICATION', 'CREATE_CUSTOMER_CREDIT_APPLICATION', 'VOID_CUSTOMER_CREDIT_APPLICATION',
               'VIEW_SUPPLIER_CREDIT_APPLICATION', 'CREATE_SUPPLIER_CREDIT_APPLICATION', 'VOID_SUPPLIER_CREDIT_APPLICATION',
               'VIEW_CUSTOMER_CREDIT_APPLICATION_DETAILED_REPORT', 'VIEW_SUPPLIER_CREDIT_APPLICATION_DETAILED_REPORT');
