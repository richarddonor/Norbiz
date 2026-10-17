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
- Migrations run while the app is stopped and **before** the new version starts. Prod runs with
  `ddl-auto=validate` (`deploy/docker-compose.prod.yml`): Hibernate never changes the schema there and the
  app refuses to start if an entity doesn't match it. **Every** entity change — including a new nullable
  column or table — needs a migration, or the release fails at startup.
- **Assume a local backend already touched prod.** Developers sometimes point a local backend
  (`ddl-auto=update`, any branch) at the prod database, so before a migration runs prod may already have
  the new tables/columns (Hibernate's shape: no `DEFAULT`s, Hibernate constraint names, enum `CHECK`s),
  rows the new version seeds on startup (`DataInitializer` permissions, default document templates,
  transaction sequence rows), or leftovers from an older branch. A migration must reach the same end
  state either way: `IF NOT EXISTS` alone isn't enough when the object may exist in Hibernate's shape
  (also set the `DEFAULT`s/constraints `init.sql` has), merge rather than insert/rename into a unique
  key the new version may have filled (see 004's permissions and `transaction_sequences`), and stop
  with a clear `RAISE EXCEPTION` instead of guessing when real data exists on both sides.
- Don't rely on Hibernate-generated DDL in a migration. Local `docker-compose.yml` still uses
  `ddl-auto=update`, which adds `CHECK (col IN (...))` for `@Enumerated(STRING)` columns when it creates a
  table and never updates it; `init.sql` declares no such constraints (004 dropped them from prod).
