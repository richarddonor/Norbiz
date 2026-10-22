-- Per-company UI theme (docs/THEMING.md): color preset or custom hex, corner radius, sidebar style, form surface,
-- motion style, and an uploaded logo. Existing companies keep today's look (default preset, SOFT/LIGHT/PLAIN) and
-- get the DOCUMENT form animation.
--
-- A local backend with ddl-auto=update may already have added these columns (Hibernate shape: no DEFAULT, nullable
-- enum columns), so set the defaults and NOT NULL either way. The MANAGE_COMPANY_THEME permission is seeded by
-- DataInitializer on startup — no row needed here.
ALTER TABLE companies ADD COLUMN IF NOT EXISTS theme_preset     VARCHAR(30);
ALTER TABLE companies ADD COLUMN IF NOT EXISTS theme_color      VARCHAR(7);
ALTER TABLE companies ADD COLUMN IF NOT EXISTS theme_radius     VARCHAR(20) NOT NULL DEFAULT 'SOFT';
ALTER TABLE companies ADD COLUMN IF NOT EXISTS theme_sidebar    VARCHAR(20) NOT NULL DEFAULT 'LIGHT';
ALTER TABLE companies ADD COLUMN IF NOT EXISTS theme_surface    VARCHAR(20) NOT NULL DEFAULT 'PLAIN';
ALTER TABLE companies ADD COLUMN IF NOT EXISTS theme_motion     VARCHAR(20) NOT NULL DEFAULT 'DOCUMENT';
ALTER TABLE companies ADD COLUMN IF NOT EXISTS logo_path        VARCHAR(255);
ALTER TABLE companies ADD COLUMN IF NOT EXISTS theme_updated_at TIMESTAMPTZ;

UPDATE companies SET theme_radius  = 'SOFT'     WHERE theme_radius  IS NULL;
UPDATE companies SET theme_sidebar = 'LIGHT'    WHERE theme_sidebar IS NULL;
UPDATE companies SET theme_surface = 'PLAIN'    WHERE theme_surface IS NULL;
UPDATE companies SET theme_motion  = 'DOCUMENT' WHERE theme_motion  IS NULL;
ALTER TABLE companies ALTER COLUMN theme_radius  SET DEFAULT 'SOFT';
ALTER TABLE companies ALTER COLUMN theme_sidebar SET DEFAULT 'LIGHT';
ALTER TABLE companies ALTER COLUMN theme_surface SET DEFAULT 'PLAIN';
ALTER TABLE companies ALTER COLUMN theme_motion  SET DEFAULT 'DOCUMENT';
ALTER TABLE companies ALTER COLUMN theme_radius  SET NOT NULL;
ALTER TABLE companies ALTER COLUMN theme_sidebar SET NOT NULL;
ALTER TABLE companies ALTER COLUMN theme_surface SET NOT NULL;
ALTER TABLE companies ALTER COLUMN theme_motion  SET NOT NULL;
