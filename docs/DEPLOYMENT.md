# Deployment

Norbiz (backend) and Norbiz-Web (frontend) are released to a Linux server that has **only Docker**
installed. Everything is built inside Docker on the server from what is pushed to GitHub.

Current target: Ubuntu VirtualBox VM `10.16.32.64`, user `vboxuser`.

## How it fits together

```
~/norbiz/                      on the server
  .env                         prod settings + secrets (chmod 600, never committed)
  ssh/                         GitHub deploy keys, ssh config, known_hosts
  src/Norbiz/                  checkout made by deploy.sh (git runs in an alpine/git container)
  src/Norbiz-Web/              checkout made by deploy.sh
  backups/                     pg_dump taken before every release (last BACKUP_KEEP kept)
  deploy.sh -> src/Norbiz/deploy/deploy.sh
```

| File | Purpose |
|---|---|
| `deploy/docker-compose.prod.yml` | db, app, redis, lgtm, web — `restart: unless-stopped` |
| `deploy/nginx.conf` | Web container config: serves the SPA, proxies `/api/*` → `app:8080` (one origin, no CORS) |
| `deploy/deploy.sh` | The release command |
| `deploy/migrate.sh` | Applies `db/migrations/*.sql` (see `db/migrations/README.md`) |
| `deploy/bootstrap.sh` | One-time server setup |
| `deploy/.env.example` | Template for `~/norbiz/.env` |

Exposed ports: `80` web (SPA + `/api`), `8080` app (direct API / Swagger UI). Postgres (5432) and
Grafana (3000) are bound to the server's localhost only — use an SSH tunnel.

The frontend is built with `VITE_API_BASE=/api`; no Norbiz-Web changes are needed.

## First-time server setup

1. From your machine, enable key-based SSH (you'll type the server password once):
   ```bash
   ssh-copy-id vboxuser@10.16.32.64
   ```
2. Let the user run Docker without sudo (on the server, then log out and back in):
   ```bash
   sudo usermod -aG docker vboxuser
   ```
3. Copy the bootstrap files up and run it:
   ```bash
   scp deploy/bootstrap.sh deploy/lib.sh deploy/.env.example vboxuser@10.16.32.64:/tmp/
   ssh vboxuser@10.16.32.64 'bash /tmp/bootstrap.sh'
   ```
   It creates `~/norbiz`, writes `~/norbiz/.env` with a random `DB_PASSWORD` and `JWT_SECRET`, and
   prints two public keys.
4. Add each key on GitHub as a **read-only deploy key**: repo → Settings → Deploy keys → Add deploy key
   (`norbiz` key → Norbiz repo, `norbiz-web` key → Norbiz-Web repo; GitHub doesn't allow one key on two repos).
5. Run bootstrap again — it clones both repos and creates the `~/norbiz/deploy.sh` symlink:
   ```bash
   ssh vboxuser@10.16.32.64 'bash /tmp/bootstrap.sh'
   ```
6. Review `~/norbiz/.env` (`PUBLIC_HOST`, `CORS_ALLOWED_ORIGINS`, `APP_MAX_HEAP` for the VM's RAM), then
   do the first release (below). The first build downloads all Maven/npm dependencies and takes a while.
   On the empty database `db/init.sql` creates the schema and the app's `DataInitializer` seeds it.

## Releasing

1. Commit and push Norbiz (including any new `db/migrations/NNN_*.sql` plus the matching `db/init.sql`
   change) and Norbiz-Web.
2. Run the release:
   ```bash
   ssh vboxuser@10.16.32.64 '~/norbiz/deploy.sh'
   ```
   Optional refs (branch, tag or SHA): `~/norbiz/deploy.sh <norbiz-ref> <norbiz-web-ref>`.
   `SKIP_FETCH=1 ~/norbiz/deploy.sh` redeploys the current checkouts without pulling.

What `deploy.sh` does, in order:

1. Pulls both repos to the requested refs, then re-runs the freshly pulled `deploy.sh`.
2. Builds the `app` and `web` images — the running version keeps serving during the build.
3. `pg_dump`s the database to `~/norbiz/backups/norbiz-<timestamp>-<sha>.sql.gz`.
4. Starts db/redis/lgtm and stops the app.
5. Runs `migrate.sh`. On failure that migration is rolled back, the previous app is restarted, and the
   release stops.
6. Starts the new app and web and waits (up to 5 min) for `/actuator/health`; prints app logs if it
   doesn't come up.
7. Prunes dangling images and old build cache.

## Database changes

Write them as `db/migrations/NNN_description.sql` — rules in `db/migrations/README.md` (idempotent,
also folded into `init.sql`, no `BEGIN`/`COMMIT`). Check state on the server:

```bash
ssh vboxuser@10.16.32.64 '~/norbiz/src/Norbiz/deploy/migrate.sh --status'
```

## Rollback

- **Code only:** `~/norbiz/deploy.sh <previous-norbiz-ref> <previous-web-ref>`. Migrations are not
  reversed, so keep them backward compatible where possible.
- **Database:** restore a pre-release backup (plain `pg_dump`; the schema is dropped and reloaded):
  ```bash
  cd ~/norbiz
  C="docker compose -p norbiz --project-directory . --env-file .env -f src/Norbiz/deploy/docker-compose.prod.yml"
  $C stop app
  $C exec -T db sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d norbiz -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"'
  gunzip -c backups/<file>.sql.gz | $C exec -T db sh -c 'psql -v ON_ERROR_STOP=1 --single-transaction -U "$POSTGRES_USER" -d norbiz'
  ~/norbiz/deploy.sh <ref-matching-the-backup>
  ```
  The backup file name contains the Norbiz commit that was about to be deployed.

## Operating

All commands on the server, from `~/norbiz`, with `$C` as above:

```bash
$C ps                      # status
$C logs -f --tail 200 app  # app logs
$C restart app
```

Grafana (traces/logs/metrics) from your machine:

```bash
ssh -L 3000:localhost:3000 vboxuser@10.16.32.64
```

then open http://localhost:3000. Postgres works the same way with `-L 5432:localhost:5432`.

The stack restarts by itself after a VM reboot (`restart: unless-stopped`, Docker enabled at boot).
