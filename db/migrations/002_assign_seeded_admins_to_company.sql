-- Login now requires the user to belong to at least one company. The seeded admin and system_admin
-- users were created without one, so give each the companies super_admin works in.
-- Idempotent: only touches a seeded admin that still has no company.
INSERT INTO user_companies (user_id, company_id)
SELECT u.id, uc.company_id
FROM users u
JOIN users sa ON sa.username = 'super_admin'
JOIN user_companies uc ON uc.user_id = sa.id
WHERE u.username IN ('admin', 'system_admin')
  AND NOT EXISTS (SELECT 1 FROM user_companies x WHERE x.user_id = u.id)
ON CONFLICT DO NOTHING;
