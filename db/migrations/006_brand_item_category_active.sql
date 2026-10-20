-- Brands and item categories get the same Active flag as the other master data: inactive records stay on
-- the items/SKUs that already use them but can't be newly picked in forms. Existing rows stay active.
-- A local backend with ddl-auto=update may already have added the column (Hibernate shape: no DEFAULT,
-- possibly nullable), so set the default and NOT NULL either way.
ALTER TABLE brands ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;
UPDATE brands SET active = TRUE WHERE active IS NULL;
ALTER TABLE brands ALTER COLUMN active SET DEFAULT TRUE;
ALTER TABLE brands ALTER COLUMN active SET NOT NULL;

ALTER TABLE item_categories ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;
UPDATE item_categories SET active = TRUE WHERE active IS NULL;
ALTER TABLE item_categories ALTER COLUMN active SET DEFAULT TRUE;
ALTER TABLE item_categories ALTER COLUMN active SET NOT NULL;
