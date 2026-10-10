# Database migrations

Schema/data changes for **existing** databases. `deploy/migrate.sh` (run by `deploy/deploy.sh` on every
release) applies each file here once, in name order, and records it in the `schema_migrations` table.
See `docs/DEPLOYMENT.md`.

Rules:

- Name: `NNN_lower_snake_description.sql` (e.g. `004_add_customer_credit_limit.sql`). Never rename or
  edit a file once it has been deployed — add a new one.
- **Fold the same change into `db/init.sql`.** A brand-new database is created from `init.sql` and then
  gets every migration run against it, so each migration must also be **idempotent**
  (`ADD COLUMN IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`, `INSERT ... ON CONFLICT DO NOTHING`,
  `UPDATE ... WHERE <not yet done>`).
- Each file runs inside one transaction together with its `schema_migrations` row — do **not** write
  `BEGIN`/`COMMIT`, and avoid statements that can't run in a transaction (`CREATE INDEX CONCURRENTLY`,
  `VACUUM`).
- Migrations run while the app is stopped and **before** the new version starts, so Hibernate
  (`ddl-auto=update`) has not created new columns yet. A migration that backfills a new column must
  create it itself.
- Purely additive entity changes (new nullable column/table) still work without a migration because of
  `ddl-auto=update`, but adding them here too keeps `init.sql` and existing databases in step.
