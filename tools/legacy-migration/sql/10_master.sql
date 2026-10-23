-- ============================================================================================
-- 10 — Master data: company, users, employees, catalog, suppliers, customers + outlet warehouses,
-- pull-out reasons, bills of materials.
--
-- Identity: Norbiz rows keep their (offset) legacy ID wherever one legacy table maps to one Norbiz
-- table (items, employees, customers, suppliers, banks, ...), so every transaction can reference them
-- without a lookup table. Outlet warehouses take their customer's ID: legacy keys outlet stock by
-- customer ID (warehouse 1 = main), and no outlet customer has ID 1. Rows with no single legacy
-- source (company, categories, groups, brands, users) get fresh IDs. 01_reset_data.sql has made room
-- (wiped this company's previous load) and shifted the legacy IDs by migration.ctx.id_offset.
-- ============================================================================================

-- ---- helpers --------------------------------------------------------------------------------

-- migration.ctx and migration.issues are created by 01_reset_data.sql.

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

-- A re-load keeps its company row (theme, logo, templates); a first load creates it.
WITH c AS (
    INSERT INTO companies (name, active, created_at, updated_at, created_by, updated_by)
    SELECT company_name, true, now(), now(), 'legacy-migration', 'legacy-migration'
    FROM migration.ctx WHERE company_id IS NULL
    RETURNING id)
UPDATE migration.ctx SET company_id = (SELECT id FROM c) WHERE company_id IS NULL;

-- Outlet warehouses take their customer's ID, the main warehouse keeps legacy ID 1.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM legacy.tblcustomers WHERE id = migration.main_wh() AND customertypeid = 2) THEN
        RAISE EXCEPTION 'Legacy customer 1 is an outlet: its warehouse would clash with the main warehouse (ID 1)';
    END IF;
END $$;

-- ---- users ----------------------------------------------------------------------------------
-- Legacy password hashes are ints and can't be converted: every new migrated user gets a password no
-- login can match, so an admin must reset it (RESET_USER_PASSWORD) before first use. No roles are
-- assigned — legacy security groups don't map 1:1 to Norbiz roles; assign them after cutover.
--
-- Users are shared across companies. A login another company already has (e.g. migrated from an
-- earlier legacy database) is the same person when the names agree — the legacy last name appears
-- in the existing display name, or, without a last name, the display name starts with the first
-- name: that user is linked to this company too. Otherwise (a different person, or a seeded admin
-- login) the login gets a "-<company word>" suffix.

CREATE TABLE migration.user_map AS
SELECT u.id AS legacy_id, u.employeeid, btrim(u.loginname) AS login,
       nullif(btrim(concat_ws(' ', nullif(btrim(e.firstname), ''), nullif(btrim(e.lastname), ''))), '') AS display_name,
       nullif(lower(btrim(e.firstname)), '') AS first_name, nullif(lower(btrim(e.lastname)), '') AS last_name,
       NULL::bigint AS existing_user_id, btrim(u.loginname) AS username
FROM legacy.tblsecurityusers u
LEFT JOIN legacy.tblemployees e ON e.id = u.employeeid;

UPDATE migration.user_map m SET existing_user_id = x.id
FROM users x
WHERE lower(x.username) = lower(m.login) AND x.username NOT IN ('admin', 'super_admin', 'system_admin')
  AND (CASE WHEN m.last_name IS NOT NULL THEN position(m.last_name IN lower(x.display_name)) > 0
            ELSE m.first_name IS NOT NULL AND lower(x.display_name) LIKE m.first_name || '%' END);

-- Suffix: the company name's last word ("DMARK MULTISALES INC. - NEOSTRATA" -> "neostrata").
UPDATE migration.user_map m SET username = m.login || '-' ||
       coalesce(nullif(lower(regexp_replace(substring((SELECT company_name FROM migration.ctx) FROM '([A-Za-z0-9]+)\W*$'), '[^A-Za-z0-9]', '', 'g')), ''), 'legacy')
WHERE m.existing_user_id IS NULL AND lower(m.login) IN (SELECT lower(username) FROM users);
UPDATE migration.user_map m SET username = x.username FROM users x WHERE x.id = m.existing_user_id;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'renamed', 'User', legacy_id, 'Login clashed with an existing Norbiz user (another person); renamed to ' || username
FROM migration.user_map WHERE existing_user_id IS NULL AND username <> login
UNION ALL
SELECT 'linked', 'User', legacy_id, 'Login ' || login || ' is the same person as existing user ' || username || '; linked to this company'
FROM migration.user_map WHERE existing_user_id IS NOT NULL;

INSERT INTO users (username, display_name, email, password, created_at, updated_at, created_by, updated_by)
SELECT m.username, coalesce(m.display_name, m.username),
       lower(regexp_replace(m.username, '[^A-Za-z0-9._-]', '', 'g')) || '.' || m.legacy_id || '@legacy.invalid',
       '!legacy-migration-password-reset-required', now(), now(), 'legacy-migration', 'legacy-migration'
FROM migration.user_map m WHERE m.existing_user_id IS NULL;

INSERT INTO user_companies (user_id, company_id)
SELECT u.id, (SELECT company_id FROM migration.ctx)
FROM users u JOIN migration.user_map m ON m.username = u.username
ON CONFLICT DO NOTHING;

-- The seeded admins work in the migrated company too (login requires at least one company).
INSERT INTO user_companies (user_id, company_id)
SELECT u.id, (SELECT company_id FROM migration.ctx) FROM users u
WHERE u.username IN ('super_admin', 'system_admin', 'admin')
ON CONFLICT DO NOTHING;

-- ---- employees ------------------------------------------------------------------------------

INSERT INTO employees (id, company_id, user_id, employee_code, first_name, last_name, active, created_at, updated_at, created_by, updated_by)
SELECT e.id, (SELECT company_id FROM migration.ctx),
       (SELECT u.id FROM users u JOIN migration.user_map m ON m.username = u.username WHERE m.employeeid = e.id ORDER BY m.legacy_id LIMIT 1),
       CASE WHEN count(*) OVER (PARTITION BY btrim(e.employeecode)) > 1
                 AND row_number() OVER (PARTITION BY btrim(e.employeecode) ORDER BY e.id) > 1
            THEN left(btrim(e.employeecode), 90) || '-' || migration.legacy_id(e.id) ELSE left(btrim(e.employeecode), 100) END,
       coalesce(migration.trunc(e.firstname, 255), migration.trunc(e.name, 255), migration.trunc(e.fullname, 255), '-'),
       coalesce(migration.trunc(e.lastname, 255), '-'),
       coalesce(e.active, true),
       coalesce(migration.at(e.dateencoded), now()), now(), 'legacy-migration', 'legacy-migration'
FROM legacy.tblemployees e;

INSERT INTO migration.issues (kind, entity, legacy_id, detail)
SELECT 'renamed', 'Employee', e.id, 'Duplicate employee code ' || btrim(l.employeecode) || ' became ' || e.employee_code
FROM employees e JOIN legacy.tblemployees l ON l.id = e.id WHERE e.employee_code <> btrim(l.employeecode);

-- Every employee credited on an outlet sale is a sales agent (Outlet Delivery Receipt requires it).
-- So is every one credited on a sales invoice (Norbiz Sales Invoices carry no agent; see docs).
INSERT INTO employee_tags (employee_id, tag)
SELECT DISTINCT agentid, 'AGENT' FROM legacy.tbloutletdeliveryreceipts WHERE agentid IS NOT NULL
UNION SELECT DISTINCT s.agentid, 'AGENT' FROM legacy.tblsalesinvoices s JOIN legacy.tblemployees e ON e.id = s.agentid;

-- Payment terms (tblTerms: "30 days" ...) as Norbiz term days (0-365).
CREATE TABLE migration.terms AS
SELECT id, least(greatest(coalesce(numberofdays, 0), 0), 365) AS days FROM legacy.tblterms;
CREATE FUNCTION migration.term_days(terms_id int) RETURNS int LANGUAGE sql STABLE AS $$
    SELECT coalesce((SELECT days FROM migration.terms WHERE id = $1), 0)
$$;

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

-- Every SM SKU (tblSMSKUs) goes under one brand, SM, whatever legacy brand it carried.
INSERT INTO brands (company_id, name, created_at, updated_at, created_by, updated_by)
VALUES ((SELECT company_id FROM migration.ctx), 'SM', now(), now(), 'legacy-migration', 'legacy-migration')
ON CONFLICT DO NOTHING;

CREATE TABLE migration.brand_map AS
SELECT b.id AS legacy_id, n.id AS brand_id
FROM legacy.tblbrands b
JOIN brands n ON n.name = migration.trunc(b.name, 255) AND n.company_id = (SELECT company_id FROM migration.ctx);

-- Items keep their legacy ID. Blank codes become LEGACY-<id>; a code used by several items stays
-- on the oldest one and the others get "-<id>" appended (item code is unique per company).
INSERT INTO items (id, company_id, item_category_id, item_group_id, item_code, name, active,
                   created_at, updated_at, created_by, updated_by)
SELECT i.id, (SELECT company_id FROM migration.ctx),
       coalesce(cm.category_id, (SELECT id FROM item_categories WHERE name = 'UNCATEGORIZED' AND company_id = (SELECT company_id FROM migration.ctx))),
       gm.group_id,
       CASE WHEN migration.trunc(i.itemcode, 100) IS NULL THEN 'LEGACY-' || migration.legacy_id(i.id)
            WHEN row_number() OVER (PARTITION BY btrim(i.itemcode) ORDER BY i.id) > 1 THEN left(btrim(i.itemcode), 88) || '-' || migration.legacy_id(i.id)
            ELSE btrim(i.itemcode) END,
       coalesce(migration.trunc(i.itemname, 255), migration.trunc(i.description, 255), migration.trunc(i.itemcode, 255), 'Item ' || migration.legacy_id(i.id)),
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

-- SKUs come from two legacy masters, SM SKUs (tblSMSKUs) and price points (tblPricePoints). A price
-- point without a real item code attaches to items by category (ItemDescriptionID = item
-- SKUDescriptionID) and price, so one code may span many items (Norbiz allows that: codes are unique
-- per item only).
--   * SM SKU: only to the items whose SMSKUNo names it (active or not) — never by category and
--     price. One that no item names is still kept as an item-less SKU.
--   * Price point: when its ItemCode is a real legacy item code, an active one goes to that item
--     only (whatever its category and price). Otherwise an active one goes to every item whose unit
--     price (PriceTypeID 1 = regular, or none) or focal price (PriceTypeID 2) equals its PricePoint.
--     One that matches no item, or is inactive, is still kept as an item-less SKU.
-- When both give an item the same code, the price point wins (it carries more columns). Every SM SKU
-- gets the SM brand; a price point keeps its own. ImonoSKUNo and tblSMSKUs.ItemID are not used.
CREATE TABLE migration.sku_items AS
SELECT id, skudescriptionid AS category_id, btrim(smskuno) AS smskuno, unitprice, focalprice,
       CASE WHEN focalprice > 0 THEN focalprice ELSE unitprice END AS sm_price
FROM legacy.tblitems;

CREATE TABLE migration.sm_skus AS
SELECT DISTINCT ON (btrim(skuno)) btrim(skuno) AS code, itemdescriptionid AS category_id, brandid, price, active
FROM legacy.tblsmskus
WHERE btrim(skuno) <> '' AND length(btrim(skuno)) <= 100
ORDER BY btrim(skuno), active DESC, skuno;

CREATE TABLE migration.price_points AS
SELECT p.id, btrim(p.skuno) AS code, p.itemdescriptionid AS category_id, p.brandid, p.pricepoint AS price,
       CASE p.pricetypeid WHEN 1 THEN 'UNIT_PRICE' WHEN 2 THEN 'FOCAL_PRICE' END AS price_type,
       p.pricetypeid, coalesce(p.active, false) AS active,
       migration.trunc(p.itemcode, 100) AS store_item_code, migration.trunc(p.barcode, 100) AS barcode,
       migration.trunc(p.vendorpart, 100) AS vendor_part, migration.trunc(p.rdsdescription, 255) AS rds_description,
       coalesce(p.isrdssku, false) AS rds_sku, coalesce(p.islandmarksku, false) AS landmark_sku,
       coalesce(migration.at(p.dateencoded), now()) AS created_at, migration.actor(p.encodedbyid) AS created_by
FROM legacy.tblpricepoints p
WHERE btrim(p.skuno) <> '' AND length(btrim(p.skuno)) <= 100 AND p.pricepoint IS NOT NULL;

CREATE TABLE migration.price_point_items AS
WITH by_code AS (
    SELECT p.id AS price_point_id, i.id AS item_id
    FROM migration.price_points p
    JOIN legacy.tblitems i ON upper(btrim(i.itemcode)) = upper(p.store_item_code)
    WHERE p.active)
SELECT price_point_id, item_id FROM by_code
UNION ALL
SELECT p.id, i.id
FROM migration.price_points p
JOIN migration.sku_items i ON i.category_id = p.category_id
                          AND p.price = CASE WHEN p.pricetypeid = 2 THEN i.focalprice ELSE i.unitprice END
WHERE p.active AND NOT EXISTS (SELECT 1 FROM by_code c WHERE c.price_point_id = p.id);

INSERT INTO item_skus (company_id, item_id, sku_code, unit_price, item_category_id, brand_id, price_type,
                       store_item_code, barcode, vendor_part, rds_description, active, rds_sku, landmark_sku,
                       created_at, updated_at, created_by, updated_by)
SELECT DISTINCT ON (item_id, code)
       (SELECT company_id FROM migration.ctx), item_id, code, round(price, 4), cm.category_id,
       CASE WHEN source = 2 THEN (SELECT id FROM brands WHERE name = 'SM' AND company_id = (SELECT company_id FROM migration.ctx))
            ELSE bm.brand_id END, price_type,
       store_item_code, barcode, vendor_part, rds_description, active, rds_sku, landmark_sku,
       created_at, now(), created_by, 'legacy-migration'
FROM (
    SELECT m.item_id, p.code, p.price, p.category_id AS legacy_category_id, p.brandid, p.price_type,
           p.store_item_code, p.barcode, p.vendor_part, p.rds_description, p.active, p.rds_sku, p.landmark_sku,
           p.created_at, p.created_by, 1 AS source, p.id AS source_order
    FROM migration.price_point_items m JOIN migration.price_points p ON p.id = m.price_point_id
    UNION ALL
    SELECT i.id, s.code, coalesce(s.price, i.sm_price, 0), s.category_id, s.brandid, NULL,
           NULL, NULL, NULL, NULL, coalesce(s.active, false), false, false,
           now(), 'legacy-migration', 2, 0
    FROM migration.sku_items i
    JOIN migration.sm_skus s ON s.code = i.smskuno
) x
LEFT JOIN migration.category_map cm ON cm.legacy_id = x.legacy_category_id
LEFT JOIN migration.brand_map bm ON bm.legacy_id = x.brandid
ORDER BY item_id, code, source, source_order;

-- Price points that went to no item: one item-less SKU each.
INSERT INTO item_skus (company_id, item_id, sku_code, unit_price, item_category_id, brand_id, price_type,
                       store_item_code, barcode, vendor_part, rds_description, active, rds_sku, landmark_sku,
                       created_at, updated_at, created_by, updated_by)
SELECT (SELECT company_id FROM migration.ctx), NULL, p.code, round(p.price, 4), cm.category_id, bm.brand_id, p.price_type,
       p.store_item_code, p.barcode, p.vendor_part, p.rds_description, p.active, p.rds_sku, p.landmark_sku,
       p.created_at, now(), p.created_by, 'legacy-migration'
FROM migration.price_points p
LEFT JOIN migration.category_map cm ON cm.legacy_id = p.category_id
LEFT JOIN migration.brand_map bm ON bm.legacy_id = p.brandid
WHERE NOT EXISTS (SELECT 1 FROM migration.price_point_items m WHERE m.price_point_id = p.id);

-- SM SKUs no item's SMSKUNo names: one item-less SKU each, under the SM brand.
INSERT INTO item_skus (company_id, item_id, sku_code, unit_price, item_category_id, brand_id, active,
                       created_at, updated_at, created_by, updated_by)
SELECT (SELECT company_id FROM migration.ctx), NULL, s.code, round(coalesce(s.price, 0), 4), cm.category_id,
       (SELECT id FROM brands WHERE name = 'SM' AND company_id = (SELECT company_id FROM migration.ctx)),
       coalesce(s.active, false), now(), now(), 'legacy-migration', 'legacy-migration'
FROM migration.sm_skus s
LEFT JOIN migration.category_map cm ON cm.legacy_id = s.category_id
WHERE NOT EXISTS (SELECT 1 FROM migration.sku_items i WHERE i.smskuno = s.code);

-- Built after the bulk load rather than maintained row by row (01_reset_data.sql drops it).
CREATE INDEX IF NOT EXISTS ITEM_SKUS_BRAND_CATEGORY_PRICE_IX ON item_skus (company_id, brand_id, item_category_id, unit_price);

-- ---- suppliers ------------------------------------------------------------------------------

INSERT INTO suppliers (id, company_id, code, name, phone, active, term_days, created_at, updated_at, created_by, updated_by)
SELECT s.id, (SELECT company_id FROM migration.ctx),
       CASE WHEN migration.trunc(s.suppliercode, 100) IS NULL THEN 'S-' || migration.legacy_id(s.id)
            WHEN row_number() OVER (PARTITION BY btrim(s.suppliercode) ORDER BY s.id) > 1 THEN left(btrim(s.suppliercode), 88) || '-' || migration.legacy_id(s.id)
            ELSE btrim(s.suppliercode) END,
       coalesce(migration.trunc(s.name, 255), 'Supplier ' || migration.legacy_id(s.id)),
       migration.trunc(s.telnum1, 50),
       coalesce(s.active, true), migration.term_days(s.termsid),
       coalesce(migration.at(s.dateencoded), now()), now(), migration.actor(s.encodedbyid), 'legacy-migration'
FROM legacy.tblsuppliers s;

-- ---- customers and warehouses ---------------------------------------------------------------
-- A customer is an OUTLET when legacy typed it so, or when it ever held stock (legacy keyed stock
-- by customer ID — three CUSTOMER-typed customers did).

CREATE TABLE migration.stock_holders AS
SELECT DISTINCT warehouseid AS customer_id FROM legacy.tbltransactiondetails WHERE warehouseid <> migration.main_wh()
UNION SELECT DISTINCT warehouseid FROM legacy.tblinventory WHERE warehouseid <> migration.main_wh();

CREATE TABLE migration.customer_map AS
SELECT c.id,
       (c.customertypeid = 2 OR c.id IN (SELECT customer_id FROM migration.stock_holders)) AS outlet,
       CASE WHEN migration.trunc(c.code, 100) IS NULL THEN 'C-' || migration.legacy_id(c.id)
            WHEN row_number() OVER (PARTITION BY btrim(c.code) ORDER BY c.id) > 1 THEN left(btrim(c.code), 88) || '-' || migration.legacy_id(c.id)
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
       CASE WHEN EXISTS (SELECT 1 FROM migration.customer_map m WHERE m.code = 'MAIN') THEN 'MAIN-' || migration.legacy_id(w.id) ELSE 'MAIN' END,
       coalesce(migration.trunc(w.name, 255), 'Main Warehouse'), true, true, false,
       coalesce(migration.at(w.dateencoded), now()), now(), 'legacy-migration', 'legacy-migration'
FROM legacy.tblwarehouses w WHERE w.id = migration.main_wh();

-- Each outlet's warehouse mirrors the customer's code/name/active, as CustomerService keeps them.
INSERT INTO warehouses (id, company_id, code, name, active, main, outlet, created_at, updated_at, created_by, updated_by)
SELECT c.id, (SELECT company_id FROM migration.ctx), m.code, coalesce(migration.trunc(c.name, 255), 'Outlet ' || migration.legacy_id(c.id)),
       coalesce(c.active, true), false, true, coalesce(migration.at(c.dateencoded), now()), now(), 'legacy-migration', 'legacy-migration'
FROM legacy.tblcustomers c JOIN migration.customer_map m ON m.id = c.id WHERE m.outlet;

INSERT INTO customers (id, company_id, code, name, type, phone, active, warehouse_id, term_days, created_at, updated_at, created_by, updated_by)
SELECT c.id, (SELECT company_id FROM migration.ctx), m.code, coalesce(migration.trunc(c.name, 255), 'Customer ' || migration.legacy_id(c.id)),
       CASE WHEN m.outlet THEN 'OUTLET' ELSE 'CUSTOMER' END,
       migration.trunc(coalesce(nullif(btrim(c.telnum1), ''), c.cellphone1), 50),
       coalesce(c.active, true), CASE WHEN m.outlet THEN c.id END, migration.term_days(c.termsid),
       coalesce(migration.at(c.dateencoded), now()), now(), migration.actor(c.encodedbyid), 'legacy-migration'
FROM legacy.tblcustomers c JOIN migration.customer_map m ON m.id = c.id;

-- ---- pull-out reasons and bills of materials ------------------------------------------------

INSERT INTO pull_out_reasons (id, company_id, name, active, created_at, updated_at, created_by, updated_by)
SELECT r.id, (SELECT company_id FROM migration.ctx),
       CASE WHEN row_number() OVER (PARTITION BY btrim(r.name) ORDER BY r.id) > 1 THEN left(btrim(r.name), 240) || ' #' || migration.legacy_id(r.id)
            ELSE coalesce(migration.trunc(r.name, 255), 'Reason ' || migration.legacy_id(r.id)) END,
       true, now(), now(), 'legacy-migration', 'legacy-migration'
FROM legacy.tblpulloutreasons r;

INSERT INTO bills_of_materials (id, company_id, code, item_id, active, created_at, updated_at, created_by, updated_by)
SELECT b.id, (SELECT company_id FROM migration.ctx),
       CASE WHEN migration.trunc(b.code, 100) IS NULL THEN 'BOM-' || migration.legacy_id(b.id)
            WHEN row_number() OVER (PARTITION BY btrim(b.code) ORDER BY b.id) > 1 THEN left(btrim(b.code), 88) || '-' || migration.legacy_id(b.id)
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


-- ---- banks and bank accounts ------------------------------------------------------------------
-- tblBanks are drawee banks (the bank a customer's check is drawn on); tblBankAccounts are the
-- company's own accounts. Legacy accounts name their bank only in their name ("bdo (Dmark Medical)"),
-- so each account's bank is the tblBanks row whose code is that first word, else a bank created
-- from it. Both keep their (offset) legacy IDs. Opening balance 0 as of the first deposit: the bank
-- book is rebuilt from the migrated deposits, transfers and disbursements.

INSERT INTO banks (id, company_id, code, name, active, created_at, updated_at, created_by, updated_by)
SELECT b.id, migration.cid(),
       CASE WHEN row_number() OVER (PARTITION BY upper(btrim(b.code)) ORDER BY b.id) > 1 OR migration.trunc(b.code, 50) IS NULL
            THEN left(coalesce(upper(btrim(b.code)), 'BANK'), 40) || '-' || migration.legacy_id(b.id) ELSE upper(btrim(b.code)) END,
       coalesce(migration.trunc(b.name, 255), migration.trunc(b.code, 255), 'Bank ' || migration.legacy_id(b.id)),
       true, now(), now(), 'legacy-migration', 'legacy-migration'
FROM legacy.tblbanks b;

CREATE TABLE migration.account_bank AS
SELECT a.id AS account_id, upper(coalesce(substring(btrim(a.name) FROM '^([A-Za-z]+)'), 'BANK')) AS bank_word FROM legacy.tblbankaccounts a;
-- Common short forms in account names.
UPDATE migration.account_bank SET bank_word = CASE bank_word WHEN 'SB' THEN 'SECURITY BANK' WHEN 'EASTWEST' THEN 'EASTWEST BANK' ELSE bank_word END;

INSERT INTO banks (company_id, code, name, active, created_at, updated_at, created_by, updated_by)
SELECT DISTINCT migration.cid(), left(a.bank_word, 50), a.bank_word, true, now(), now(), 'legacy-migration', 'legacy-migration'
FROM migration.account_bank a
WHERE NOT EXISTS (SELECT 1 FROM banks b WHERE b.company_id = migration.cid() AND (upper(b.code) = a.bank_word OR upper(b.name) = a.bank_word));

INSERT INTO bank_accounts (id, company_id, bank_id, name, account_number, opening_balance, opening_balance_date, active,
                           created_at, updated_at, created_by, updated_by)
SELECT a.id, migration.cid(),
       (SELECT b.id FROM banks b WHERE b.company_id = migration.cid() AND (upper(b.code) = m.bank_word OR upper(b.name) = m.bank_word) ORDER BY b.id LIMIT 1),
       coalesce(migration.trunc(a.name, 255), 'Account ' || migration.legacy_id(a.id)),
       CASE WHEN row_number() OVER (PARTITION BY btrim(a.number) ORDER BY a.id) > 1 OR migration.trunc(a.number, 50) IS NULL
            THEN left(coalesce(btrim(a.number), 'ACCOUNT'), 40) || '-' || migration.legacy_id(a.id) ELSE btrim(a.number) END,
       0, (SELECT migration.day(min(d.date)) FROM legacy.tbldeposits d WHERE d.bankid = a.id),
       coalesce(a.active, true), now(), now(), 'legacy-migration', 'legacy-migration'
FROM legacy.tblbankaccounts a JOIN migration.account_bank m ON m.account_id = a.id;
