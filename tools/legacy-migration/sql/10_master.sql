-- ============================================================================================
-- 10 — Master data: company, users, employees, catalog, suppliers, customers + outlet warehouses,
-- pull out reasons, bills of materials.
--
-- Identity: Norbiz rows keep their legacy ID wherever one legacy table maps to one Norbiz table
-- (items, employees, customers, suppliers, ...), so every transaction can reference them without
-- a lookup table. Outlet warehouses take their customer's ID: legacy keys outlet stock by customer
-- ID (warehouse 1 = main), and no customer has ID 1. Rows with no single legacy source (company,
-- categories, groups, brands, users) get fresh IDs. Requires an empty target (see 00_reset.sql).
-- ============================================================================================

-- ---- helpers --------------------------------------------------------------------------------

-- Run context: the company every migrated row belongs to, the cutover instant, and the actor
-- recorded on rows that have no legacy author.
CREATE TABLE migration.ctx (company_id bigint, cutover timestamptz NOT NULL, actor text NOT NULL);
INSERT INTO migration.ctx (cutover, actor) VALUES (now(), 'legacy-migration');

-- Anything the loader had to fix or couldn't map — listed by report.sql.
CREATE TABLE migration.issues (kind text NOT NULL, entity text NOT NULL, legacy_id bigint, detail text);

-- A legacy business date as Norbiz stores it: the UTC-midnight instant of that calendar day.
-- Impossible dates (before 2000, or more than a year past the cutover) come back NULL.
CREATE FUNCTION migration.day(ts timestamp) RETURNS timestamptz LANGUAGE sql STABLE AS $$
    SELECT CASE WHEN ts IS NULL OR ts < '2000-01-01' OR ts > (SELECT cutover FROM migration.ctx) + interval '1 year'
                THEN NULL ELSE (ts::date)::timestamp AT TIME ZONE 'UTC' END
$$;

-- A legacy audit timestamp (local Philippine time) as an instant.
CREATE FUNCTION migration.at(ts timestamp) RETURNS timestamptz LANGUAGE sql STABLE AS $$
    SELECT CASE WHEN ts IS NULL OR ts < '2000-01-01' OR ts > (SELECT cutover FROM migration.ctx) + interval '1 year'
                THEN NULL ELSE ts AT TIME ZONE 'Asia/Manila' END
$$;

CREATE FUNCTION migration.trunc(s text, n int) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT nullif(left(btrim(s), n), '')
$$;

-- ---- company --------------------------------------------------------------------------------

WITH c AS (
    INSERT INTO companies (name, active, created_at, updated_at, created_by, updated_by)
    SELECT coalesce(migration.trunc(name, 255), 'Legacy Company'), true, now(), now(), 'legacy-migration', 'legacy-migration'
    FROM legacy.tblcompany ORDER BY id LIMIT 1
    RETURNING id)
UPDATE migration.ctx SET company_id = (SELECT id FROM c);

-- ---- users ----------------------------------------------------------------------------------
-- Legacy password hashes are ints and can't be converted: every migrated user gets a password no
-- login can match, so an admin must reset it (RESET_USER_PASSWORD) before first use. No roles are
-- assigned — legacy security groups don't map 1:1 to Norbiz roles; assign them after cutover.

CREATE TABLE migration.user_map AS
SELECT u.id AS legacy_id, u.employeeid,
       CASE WHEN lower(btrim(u.loginname)) IN (SELECT lower(username) FROM users)
            THEN btrim(u.loginname) || '-legacy' ELSE btrim(u.loginname) END AS username,
       nullif(btrim(concat_ws(' ', nullif(btrim(e.firstname), ''), nullif(btrim(e.lastname), ''))), '') AS display_name
FROM legacy.tblsecurityusers u
LEFT JOIN legacy.tblemployees e ON e.id = u.employeeid;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'renamed', 'User', legacy_id, 'Login clashed with a seeded Norbiz user; renamed to ' || username
FROM migration.user_map WHERE username LIKE '%-legacy';

INSERT INTO users (username, display_name, email, password, created_at, updated_at, created_by, updated_by)
SELECT m.username, coalesce(m.display_name, m.username),
       lower(regexp_replace(m.username, '[^A-Za-z0-9._-]', '', 'g')) || '.' || m.legacy_id || '@legacy.invalid',
       '!legacy-migration-password-reset-required', now(), now(), 'legacy-migration', 'legacy-migration'
FROM migration.user_map m;

INSERT INTO user_companies (user_id, company_id)
SELECT u.id, (SELECT company_id FROM migration.ctx)
FROM users u JOIN migration.user_map m ON m.username = u.username;

-- super_admin works in the migrated company too.
INSERT INTO user_companies (user_id, company_id)
SELECT u.id, (SELECT company_id FROM migration.ctx) FROM users u WHERE u.username = 'super_admin'
ON CONFLICT DO NOTHING;

-- ---- employees ------------------------------------------------------------------------------

INSERT INTO employees (id, company_id, user_id, employee_code, first_name, last_name, active, created_at, updated_at, created_by, updated_by)
SELECT e.id, (SELECT company_id FROM migration.ctx),
       (SELECT u.id FROM users u JOIN migration.user_map m ON m.username = u.username WHERE m.employeeid = e.id ORDER BY m.legacy_id LIMIT 1),
       CASE WHEN count(*) OVER (PARTITION BY btrim(e.employeecode)) > 1
                 AND row_number() OVER (PARTITION BY btrim(e.employeecode) ORDER BY e.id) > 1
            THEN left(btrim(e.employeecode), 90) || '-' || e.id ELSE left(btrim(e.employeecode), 100) END,
       coalesce(migration.trunc(e.firstname, 255), migration.trunc(e.name, 255), migration.trunc(e.fullname, 255), '-'),
       coalesce(migration.trunc(e.lastname, 255), '-'),
       coalesce(e.active, true),
       coalesce(migration.at(e.dateencoded), now()), now(), 'legacy-migration', 'legacy-migration'
FROM legacy.tblemployees e;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'renamed', 'Employee', e.id, 'Duplicate employee code ' || btrim(l.employeecode) || ' became ' || e.employee_code
FROM employees e JOIN legacy.tblemployees l ON l.id = e.id WHERE e.employee_code <> btrim(l.employeecode);

-- Every employee credited on an outlet sale is a sales agent (Outlet Delivery Receipt requires it).
INSERT INTO employee_tags (employee_id, tag)
SELECT DISTINCT agentid, 'AGENT' FROM legacy.tbloutletdeliveryreceipts WHERE agentid IS NOT NULL;

-- Who a legacy CreatedByID / VoidedBy (an employee ID) becomes on Norbiz rows: the employee's
-- login when they had one, otherwise a "legacy-<employee code>" marker.
CREATE TABLE migration.actor AS
SELECT e.id AS employee_id,
       coalesce((SELECT m.username FROM migration.user_map m WHERE m.employeeid = e.id ORDER BY m.legacy_id LIMIT 1),
                'legacy-' || left(btrim(e.employeecode), 90)) AS username
FROM legacy.tblemployees e;
CREATE UNIQUE INDEX ON migration.actor (employee_id);

CREATE FUNCTION migration.actor(employee_id int) RETURNS text LANGUAGE sql STABLE AS $$
    SELECT coalesce((SELECT username FROM migration.actor a WHERE a.employee_id = $1), 'legacy-migration')
$$;

-- ---- catalog --------------------------------------------------------------------------------

-- Item categories <- tblItemDescriptions (EARRING, TIARA, ...), one per distinct name.
INSERT INTO item_categories (company_id, name, created_at, updated_at, created_by, updated_by)
SELECT (SELECT company_id FROM migration.ctx), n, now(), now(), 'legacy-migration', 'legacy-migration'
FROM (SELECT DISTINCT coalesce(migration.trunc(name, 255), 'UNCATEGORIZED') AS n FROM legacy.tblitemdescriptions
      UNION SELECT 'UNCATEGORIZED') x;

CREATE TABLE migration.category_map AS
SELECT d.id AS legacy_id, c.id AS category_id
FROM legacy.tblitemdescriptions d
JOIN item_categories c ON c.name = coalesce(migration.trunc(d.name, 255), 'UNCATEGORIZED')
                      AND c.company_id = (SELECT company_id FROM migration.ctx);

-- Item groups <- tblCategoryChartsOrig (product lines such as "LUXOR (LXR)"); the code in
-- parentheses becomes the BN initials. Duplicate names get the legacy ID appended.
CREATE TABLE migration.group_map AS
SELECT g.id AS legacy_id,
       CASE WHEN row_number() OVER (PARTITION BY btrim(g.name) ORDER BY g.id) > 1
            THEN left(btrim(g.name), 240) || ' #' || g.id ELSE coalesce(migration.trunc(g.name, 255), 'GROUP ' || g.id) END AS name,
       left(substring(g.name FROM '\(([^)]+)\)'), 20) AS bn_initials,
       NULL::bigint AS group_id
FROM legacy.tblcategorychartsorig g;

INSERT INTO item_groups (company_id, name, bn_initials, commission_rate, focal_commission_rate, active,
                         created_at, updated_at, created_by, updated_by)
SELECT (SELECT company_id FROM migration.ctx), name, bn_initials, 0, 0, true, now(), now(), 'legacy-migration', 'legacy-migration'
FROM migration.group_map;

UPDATE migration.group_map m SET group_id = g.id
FROM item_groups g WHERE g.name = m.name AND g.company_id = (SELECT company_id FROM migration.ctx);

INSERT INTO brands (company_id, name, created_at, updated_at, created_by, updated_by)
SELECT (SELECT company_id FROM migration.ctx), migration.trunc(name, 255), now(), now(), 'legacy-migration', 'legacy-migration'
FROM legacy.tblbrands WHERE migration.trunc(name, 255) IS NOT NULL
ON CONFLICT DO NOTHING;

-- Items keep their legacy ID. Blank codes become LEGACY-<id>; a code used by several items stays
-- on the oldest one and the others get "-<id>" appended (item code is unique per company).
INSERT INTO items (id, company_id, item_category_id, item_group_id, item_code, name, active,
                   created_at, updated_at, created_by, updated_by)
SELECT i.id, (SELECT company_id FROM migration.ctx),
       coalesce(cm.category_id, (SELECT id FROM item_categories WHERE name = 'UNCATEGORIZED' AND company_id = (SELECT company_id FROM migration.ctx))),
       gm.group_id,
       CASE WHEN migration.trunc(i.itemcode, 100) IS NULL THEN 'LEGACY-' || i.id
            WHEN row_number() OVER (PARTITION BY btrim(i.itemcode) ORDER BY i.id) > 1 THEN left(btrim(i.itemcode), 88) || '-' || i.id
            ELSE btrim(i.itemcode) END,
       coalesce(migration.trunc(i.itemname, 255), migration.trunc(i.description, 255), migration.trunc(i.itemcode, 255), 'Item ' || i.id),
       i.active,
       coalesce(migration.at(i.dateencoded), now()), now(), migration.actor(i.encodedbyid), 'legacy-migration'
FROM legacy.tblitems i
LEFT JOIN migration.category_map cm ON cm.legacy_id = i.skudescriptionid
LEFT JOIN migration.group_map gm ON gm.legacy_id = i.categoryid;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'renamed', 'Item', it.id,
       CASE WHEN migration.trunc(l.itemcode, 100) IS NULL THEN 'Blank item code became ' || it.item_code
            ELSE 'Duplicate item code ' || btrim(l.itemcode) || ' became ' || it.item_code END
FROM items it JOIN legacy.tblitems l ON l.id = it.id
WHERE it.item_code IS DISTINCT FROM btrim(l.itemcode);

INSERT INTO item_prices (item_id, price_type, amount, created_at, updated_at, created_by, updated_by)
SELECT i.id, p.price_type, coalesce(p.amount, 0), now(), now(), 'legacy-migration', 'legacy-migration'
FROM legacy.tblitems i
CROSS JOIN LATERAL (VALUES ('UNIT_PRICE', i.unitprice), ('COST_PRICE', i.costprice),
                           ('FOCAL_PRICE', i.focalprice), ('MARKDOWN_PRICE', i.markdownprice)) p(price_type, amount);

INSERT INTO item_tags (item_id, tag) SELECT id, 'INVENTORY' FROM legacy.tblitems WHERE iinventory;

-- SKUs: the SM SKU master (with its own price) first, then each item's SM / Imono SKU columns.
-- sku_code is unique across all of Norbiz, so a code seen twice stays with its first owner.
WITH candidates AS (
    SELECT btrim(s.skuno) AS code, s.itemid, s.price, 1 AS pri, s.itemid AS ord FROM legacy.tblsmskus s
    WHERE s.itemid IS NOT NULL AND btrim(s.skuno) <> '' AND EXISTS (SELECT 1 FROM legacy.tblitems i WHERE i.id = s.itemid)
    UNION ALL SELECT btrim(i.smskuno), i.id, i.unitprice, 2, i.id FROM legacy.tblitems i WHERE btrim(i.smskuno) <> ''
    UNION ALL SELECT btrim(i.imonoskuno), i.id, i.unitprice, 3, i.id FROM legacy.tblitems i WHERE btrim(i.imonoskuno) <> ''),
ranked AS (SELECT *, row_number() OVER (PARTITION BY code ORDER BY pri, ord) AS rn FROM candidates WHERE length(code) <= 100)
INSERT INTO item_skus (item_id, sku_code, unit_price, created_at, updated_at, created_by, updated_by)
SELECT itemid, code, coalesce(price, 0), now(), now(), 'legacy-migration', 'legacy-migration' FROM ranked WHERE rn = 1;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'dropped', 'ItemSku', i.id, 'SKU ' || btrim(i.smskuno) || ' is already used by item ' || s.item_id
FROM legacy.tblitems i JOIN item_skus s ON s.sku_code = btrim(i.smskuno)
WHERE btrim(i.smskuno) <> '' AND s.item_id <> i.id;

-- ---- suppliers ------------------------------------------------------------------------------

INSERT INTO suppliers (id, company_id, code, name, phone, active, created_at, updated_at, created_by, updated_by)
SELECT s.id, (SELECT company_id FROM migration.ctx),
       CASE WHEN migration.trunc(s.suppliercode, 100) IS NULL THEN 'S-' || s.id
            WHEN row_number() OVER (PARTITION BY btrim(s.suppliercode) ORDER BY s.id) > 1 THEN left(btrim(s.suppliercode), 88) || '-' || s.id
            ELSE btrim(s.suppliercode) END,
       coalesce(migration.trunc(s.name, 255), 'Supplier ' || s.id),
       migration.trunc(s.telnum1, 50),
       coalesce(s.active, true),
       coalesce(migration.at(s.dateencoded), now()), now(), migration.actor(s.encodedbyid), 'legacy-migration'
FROM legacy.tblsuppliers s;

-- ---- customers and warehouses ---------------------------------------------------------------
-- A customer is an OUTLET when legacy typed it so, or when it ever held stock (legacy keyed stock
-- by customer ID — three CUSTOMER-typed customers did).

CREATE TABLE migration.stock_holders AS
SELECT DISTINCT warehouseid AS customer_id FROM legacy.tbltransactiondetails WHERE warehouseid <> 1
UNION SELECT DISTINCT warehouseid FROM legacy.tblinventory WHERE warehouseid <> 1;

CREATE TABLE migration.customer_map AS
SELECT c.id,
       (c.customertypeid = 2 OR c.id IN (SELECT customer_id FROM migration.stock_holders)) AS outlet,
       CASE WHEN migration.trunc(c.code, 100) IS NULL THEN 'C-' || c.id
            WHEN row_number() OVER (PARTITION BY btrim(c.code) ORDER BY c.id) > 1 THEN left(btrim(c.code), 88) || '-' || c.id
            ELSE btrim(c.code) END AS code
FROM legacy.tblcustomers c;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'retyped', 'Customer', c.id, 'Legacy type ' || coalesce(t.name, c.customertypeid::text) || ' but it held stock — migrated as an OUTLET'
FROM legacy.tblcustomers c JOIN migration.customer_map m ON m.id = c.id LEFT JOIN legacy.tblcustomertypes t ON t.id = c.customertypeid
WHERE m.outlet AND c.customertypeid <> 2;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'renamed', 'Customer', c.id, coalesce('Duplicate code ' || btrim(c.code), 'Blank code') || ' became ' || m.code
FROM legacy.tblcustomers c JOIN migration.customer_map m ON m.id = c.id WHERE m.code IS DISTINCT FROM btrim(c.code);

INSERT INTO warehouses (id, company_id, code, name, active, main, outlet, created_at, updated_at, created_by, updated_by)
SELECT w.id, (SELECT company_id FROM migration.ctx),
       CASE WHEN EXISTS (SELECT 1 FROM migration.customer_map m WHERE m.code = 'MAIN') THEN 'MAIN-' || w.id ELSE 'MAIN' END,
       coalesce(migration.trunc(w.name, 255), 'Main Warehouse'), true, true, false,
       coalesce(migration.at(w.dateencoded), now()), now(), 'legacy-migration', 'legacy-migration'
FROM legacy.tblwarehouses w WHERE w.id = 1;

-- Each outlet's warehouse mirrors the customer's code/name/active, as CustomerService keeps them.
INSERT INTO warehouses (id, company_id, code, name, active, main, outlet, created_at, updated_at, created_by, updated_by)
SELECT c.id, (SELECT company_id FROM migration.ctx), m.code, coalesce(migration.trunc(c.name, 255), 'Outlet ' || c.id),
       coalesce(c.active, true), false, true, coalesce(migration.at(c.dateencoded), now()), now(), 'legacy-migration', 'legacy-migration'
FROM legacy.tblcustomers c JOIN migration.customer_map m ON m.id = c.id WHERE m.outlet;

INSERT INTO customers (id, company_id, code, name, type, phone, active, warehouse_id, created_at, updated_at, created_by, updated_by)
SELECT c.id, (SELECT company_id FROM migration.ctx), m.code, coalesce(migration.trunc(c.name, 255), 'Customer ' || c.id),
       CASE WHEN m.outlet THEN 'OUTLET' ELSE 'CUSTOMER' END,
       migration.trunc(coalesce(nullif(btrim(c.telnum1), ''), c.cellphone1), 50),
       coalesce(c.active, true), CASE WHEN m.outlet THEN c.id END,
       coalesce(migration.at(c.dateencoded), now()), now(), migration.actor(c.encodedbyid), 'legacy-migration'
FROM legacy.tblcustomers c JOIN migration.customer_map m ON m.id = c.id;

-- ---- pull out reasons and bills of materials ------------------------------------------------

INSERT INTO pull_out_reasons (id, company_id, name, active, created_at, updated_at, created_by, updated_by)
SELECT r.id, (SELECT company_id FROM migration.ctx),
       CASE WHEN row_number() OVER (PARTITION BY btrim(r.name) ORDER BY r.id) > 1 THEN left(btrim(r.name), 240) || ' #' || r.id
            ELSE coalesce(migration.trunc(r.name, 255), 'Reason ' || r.id) END,
       true, now(), now(), 'legacy-migration', 'legacy-migration'
FROM legacy.tblpulloutreasons r;

INSERT INTO bills_of_materials (id, company_id, code, item_id, active, created_at, updated_at, created_by, updated_by)
SELECT b.id, (SELECT company_id FROM migration.ctx),
       CASE WHEN migration.trunc(b.code, 100) IS NULL THEN 'BOM-' || b.id
            WHEN row_number() OVER (PARTITION BY btrim(b.code) ORDER BY b.id) > 1 THEN left(btrim(b.code), 88) || '-' || b.id
            ELSE btrim(b.code) END,
       b.itemid, true, now(), now(), 'legacy-migration', 'legacy-migration'
FROM legacy.tblbillofmaterial b WHERE EXISTS (SELECT 1 FROM legacy.tblitems i WHERE i.id = b.itemid);

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'renamed', 'BillOfMaterial', b.id, 'Duplicate code ' || btrim(l.code) || ' became ' || b.code
FROM bills_of_materials b JOIN legacy.tblbillofmaterial l ON l.id = b.id WHERE b.code IS DISTINCT FROM btrim(l.code);

-- Components: summed per item; a component equal to the output itself is dropped (Norbiz rejects it).
INSERT INTO bill_of_material_lines (bill_of_material_id, item_id, quantity, line_number, created_at, updated_at, created_by, updated_by)
SELECT d.masterid, d.itemid, sum(d.quantity), row_number() OVER (PARTITION BY d.masterid ORDER BY min(d.id)),
       now(), now(), 'legacy-migration', 'legacy-migration'
FROM legacy.tblbillofmaterialrawdetail d
JOIN bills_of_materials b ON b.id = d.masterid
WHERE d.itemid <> b.item_id AND d.quantity > 0 AND EXISTS (SELECT 1 FROM legacy.tblitems i WHERE i.id = d.itemid)
GROUP BY d.masterid, d.itemid;
