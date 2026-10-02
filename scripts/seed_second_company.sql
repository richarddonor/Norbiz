-- ============================================================
-- Seeds a second company for local multi-tenancy testing.
--
-- db/init.sql only runs once, on first container start, so it can't be
-- used to add data to an already-initialized database. Run this manually
-- against a running instance instead:
--
--   psql -h localhost -U norbiz -d norbiz -f scripts/seed_second_company.sql
--
-- Change the company name and plaintext password below before running.
-- ============================================================

INSERT INTO companies (name) VALUES ('Second Corp')
ON CONFLICT DO NOTHING;

-- Dedicated admin user scoped only to the new company, for testing
-- single-company login and cross-tenant isolation (a user with this
-- role/company should never see Norbiz-scoped records).
INSERT INTO users (username, email, password) VALUES
    ('second_admin', 'Second.admin@norbiz.com', crypt('change-me-admin', gen_salt('bf', 10)))
ON CONFLICT (username) DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u JOIN roles r ON true
WHERE u.username = 'Second_admin' AND r.name = 'ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO user_companies (user_id, company_id)
SELECT u.id, c.id FROM users u JOIN companies c ON true
WHERE u.username = 'Second_admin' AND c.name = 'Second Corp'
ON CONFLICT DO NOTHING;

-- Also give super_admin access to the new company (in addition to Norbiz),
-- so the login company-picker flow can be exercised end to end.
INSERT INTO user_companies (user_id, company_id)
SELECT u.id, c.id FROM users u JOIN companies c ON true
WHERE u.username = 'super_admin' AND c.name = 'Second Corp'
ON CONFLICT DO NOTHING;
