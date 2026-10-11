-- Gives every master-data record without one a CREATE audit log, so its change history starts
-- with "Created" instead of being empty. Records written by bulk SQL (the legacy loader, init.sql
-- seeds) never passed through AuditableEntityListener and so have no audit trail at all.
--
-- The snapshot has the listener's shape (docs/AUDIT.md): scalars as strings, to-one associations
-- as the related id, @ElementCollection/@ManyToMany as a sorted array, fields in entity
-- declaration order with the Auditable fields after them. The log is dated at the record's
-- created_at and attributed to its created_by.
--
-- A record already edited through the app has UPDATE logs; its snapshot is rolled back to the
-- values it was created with (each field's oldValue in its earliest UPDATE log).
--
-- Only the records themselves: item SKUs/prices and BOM lines (audited as parts of their parent)
-- are not backfilled.
--
-- Idempotent: skips any record that already has a CREATE log. Also run by the legacy loader
-- (tools/legacy-migration/migrate.py) after every load.

-- Java Instant.toString(): UTC, fraction in groups of 3 digits, none when zero.
CREATE FUNCTION pg_temp.audit_instant(ts timestamptz) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT to_char(ts AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS')
        || CASE WHEN extract(microseconds FROM ts)::bigint % 1000000 = 0 THEN ''
                WHEN extract(microseconds FROM ts)::bigint % 1000 = 0 THEN to_char(ts AT TIME ZONE 'UTC', '.MS')
                ELSE to_char(ts AT TIME ZONE 'UTC', '.US') END
        || 'Z'
$$;

-- The snapshot with each field that later UPDATE logs changed put back to its first oldValue.
CREATE FUNCTION pg_temp.audit_original(p_type text, p_id bigint, p_snapshot json) RETURNS json LANGUAGE sql STABLE AS $$
    WITH firsts AS (
        SELECT DISTINCT ON (c->>'field') c->>'field' AS field, c->'oldValue' AS old_value
        FROM (SELECT id, changed_at, changes FROM audit_logs
              WHERE entity_type = p_type AND entity_id = p_id AND action = 'UPDATE'
                AND left(ltrim(changes), 1) = '[') a
        CROSS JOIN LATERAL json_array_elements(a.changes::json) c
        ORDER BY c->>'field', a.changed_at, a.id
    )
    SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM firsts) THEN p_snapshot
                ELSE (SELECT json_object_agg(s.key, CASE WHEN f.field IS NULL THEN s.value ELSE f.old_value END ORDER BY s.ord)
                      FROM json_each(p_snapshot) WITH ORDINALITY s(key, value, ord)
                      LEFT JOIN firsts f ON f.field = s.key) END
$$;

CREATE PROCEDURE pg_temp.audit_backfill(p_type text, p_rows regclass, p_snapshot text) LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format($q$
        INSERT INTO audit_logs (entity_type, entity_id, change_set, action, changed_by, changed_at, changes)
        SELECT %1$L, r.id, gen_random_uuid()::text, 'CREATE', coalesce(r.created_by, 'system'), coalesce(r.created_at, now()),
               pg_temp.audit_original(%1$L, r.id, %3$s)::text
        FROM %2$s r
        WHERE NOT EXISTS (SELECT 1 FROM audit_logs a
                          WHERE a.entity_type = %1$L AND a.entity_id = r.id AND a.action = 'CREATE')
    $q$, p_type, p_rows, p_snapshot);
END
$$;

CALL pg_temp.audit_backfill('Brand', 'brands', $s$json_build_object(
    'id', r.id::text, 'company', r.company_id::text, 'name', r.name,
    'createdAt', pg_temp.audit_instant(r.created_at), 'updatedAt', pg_temp.audit_instant(r.updated_at),
    'createdBy', r.created_by, 'updatedBy', r.updated_by)$s$);

CALL pg_temp.audit_backfill('ItemCategory', 'item_categories', $s$json_build_object(
    'id', r.id::text, 'company', r.company_id::text, 'name', r.name,
    'createdAt', pg_temp.audit_instant(r.created_at), 'updatedAt', pg_temp.audit_instant(r.updated_at),
    'createdBy', r.created_by, 'updatedBy', r.updated_by)$s$);

CALL pg_temp.audit_backfill('ItemGroup', 'item_groups', $s$json_build_object(
    'id', r.id::text, 'company', r.company_id::text, 'name', r.name, 'description', r.description,
    'bnInitials', r.bn_initials, 'commissionRate', r.commission_rate::text,
    'focalCommissionRate', r.focal_commission_rate::text, 'active', r.active::text,
    'createdAt', pg_temp.audit_instant(r.created_at), 'updatedAt', pg_temp.audit_instant(r.updated_at),
    'createdBy', r.created_by, 'updatedBy', r.updated_by)$s$);

CALL pg_temp.audit_backfill('Item', 'items', $s$json_build_object(
    'id', r.id::text, 'company', r.company_id::text, 'itemCategory', r.item_category_id::text,
    'itemGroup', r.item_group_id::text, 'itemCode', r.item_code, 'name', r.name,
    'imagePath', r.image_path, 'active', r.active::text,
    'createdAt', pg_temp.audit_instant(r.created_at), 'updatedAt', pg_temp.audit_instant(r.updated_at),
    'createdBy', r.created_by, 'updatedBy', r.updated_by,
    'tags', (SELECT coalesce(json_agg(t.tag ORDER BY t.tag COLLATE "C"), '[]'::json)
             FROM item_tags t WHERE t.item_id = r.id))$s$);

CALL pg_temp.audit_backfill('Employee', 'employees', $s$json_build_object(
    'id', r.id::text, 'company', r.company_id::text, 'user', r.user_id::text,
    'employeeCode', r.employee_code, 'firstName', r.first_name, 'lastName', r.last_name,
    'email', r.email, 'phone', r.phone, 'active', r.active::text,
    'createdAt', pg_temp.audit_instant(r.created_at), 'updatedAt', pg_temp.audit_instant(r.updated_at),
    'createdBy', r.created_by, 'updatedBy', r.updated_by,
    'tags', (SELECT coalesce(json_agg(t.tag ORDER BY t.tag COLLATE "C"), '[]'::json)
             FROM employee_tags t WHERE t.employee_id = r.id))$s$);

CALL pg_temp.audit_backfill('Warehouse', 'warehouses', $s$json_build_object(
    'id', r.id::text, 'company', r.company_id::text, 'code', r.code, 'name', r.name,
    'active', r.active::text, 'main', r.main::text, 'outlet', r.outlet::text,
    'createdAt', pg_temp.audit_instant(r.created_at), 'updatedAt', pg_temp.audit_instant(r.updated_at),
    'createdBy', r.created_by, 'updatedBy', r.updated_by)$s$);

CALL pg_temp.audit_backfill('Supplier', 'suppliers', $s$json_build_object(
    'id', r.id::text, 'company', r.company_id::text, 'code', r.code, 'name', r.name,
    'email', r.email, 'phone', r.phone, 'active', r.active::text,
    'createdAt', pg_temp.audit_instant(r.created_at), 'updatedAt', pg_temp.audit_instant(r.updated_at),
    'createdBy', r.created_by, 'updatedBy', r.updated_by)$s$);

CALL pg_temp.audit_backfill('Customer', 'customers', $s$json_build_object(
    'id', r.id::text, 'company', r.company_id::text, 'code', r.code, 'name', r.name, 'type', r.type,
    'email', r.email, 'phone', r.phone, 'active', r.active::text, 'warehouse', r.warehouse_id::text,
    'createdAt', pg_temp.audit_instant(r.created_at), 'updatedAt', pg_temp.audit_instant(r.updated_at),
    'createdBy', r.created_by, 'updatedBy', r.updated_by)$s$);

CALL pg_temp.audit_backfill('PullOutReason', 'pull_out_reasons', $s$json_build_object(
    'id', r.id::text, 'company', r.company_id::text, 'name', r.name, 'active', r.active::text,
    'createdAt', pg_temp.audit_instant(r.created_at), 'updatedAt', pg_temp.audit_instant(r.updated_at),
    'createdBy', r.created_by, 'updatedBy', r.updated_by)$s$);

CALL pg_temp.audit_backfill('BillOfMaterial', 'bills_of_materials', $s$json_build_object(
    'id', r.id::text, 'company', r.company_id::text, 'code', r.code, 'item', r.item_id::text,
    'active', r.active::text,
    'createdAt', pg_temp.audit_instant(r.created_at), 'updatedAt', pg_temp.audit_instant(r.updated_at),
    'createdBy', r.created_by, 'updatedBy', r.updated_by)$s$);

-- User.password is @AuditExclude.
CALL pg_temp.audit_backfill('User', 'users', $s$json_build_object(
    'id', r.id::text, 'username', r.username, 'displayName', r.display_name, 'email', r.email,
    'createdAt', pg_temp.audit_instant(r.created_at), 'updatedAt', pg_temp.audit_instant(r.updated_at),
    'createdBy', r.created_by, 'updatedBy', r.updated_by,
    'companies', (SELECT coalesce(json_agg(uc.company_id::text ORDER BY uc.company_id::text COLLATE "C"), '[]'::json)
                  FROM user_companies uc WHERE uc.user_id = r.id),
    'roles', (SELECT coalesce(json_agg(ur.role_id::text ORDER BY ur.role_id::text COLLATE "C"), '[]'::json)
              FROM user_roles ur WHERE ur.user_id = r.id))$s$);
