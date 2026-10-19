# Deployment

Norbiz (backend) and Norbiz-Web (frontend) are released to a Linux server that has **only Docker**
installed. Everything is built inside Docker on the server from what is pushed to GitHub.

Current target: Ubuntu 24.04 KVM VM `10.18.18.18`, user `rdonor` (prod moved off the VirtualBox VM 10.16.32.64 — see `docs/SERVER_REQUIREMENTS.md#migrating-prod-from-10163264`).
Host requirements and one-time host preparation (Docker, swap, log rotation): `docs/SERVER_REQUIREMENTS.md`.

## How it fits together

```
~/norbiz/                      on the server
  .env                         prod settings + secrets (chmod 600, never committed)
  ssh/                         GitHub deploy keys, ssh config, known_hosts
  src/Norbiz/                  checkout made by deploy.sh (git runs in an alpine/git container)
  src/Norbiz-Web/              checkout made by deploy.sh
  backups/                     pg_dump taken before every release (last BACKUP_KEEP kept)
  certs/                       tls.crt / tls.key — the HTTPS certificate (PEM), only ever on the server
  deploy.sh -> src/Norbiz/deploy/deploy.sh
```

| File | Purpose |
|---|---|
| `deploy/docker-compose.prod.yml` | db, app, redis, embedder, web — `restart: unless-stopped`; `lgtm` only under the opt-in `observability` profile |
| `deploy/nginx.conf` | Web container config: HTTPS on 443 (80 redirects), serves the SPA, proxies `/api/*` → `app:8080` (one origin, no CORS) |
| `deploy/deploy.sh` | The release command |
| `deploy/migrate.sh` | Applies `db/migrations/*.sql` (see `db/migrations/README.md`) |
| `deploy/bootstrap.sh` | One-time server setup |
| `deploy/.env.example` | Template for `~/norbiz/.env` |

Exposed ports: `443` web (SPA + `/api`, HTTPS), `80` (redirects to HTTPS), `5432` Postgres (database
`norbiz`, user/password = `DB_USERNAME`/`DB_PASSWORD` from `~/norbiz/.env`). The app (8080) and Grafana (3000) are bound
to the server's localhost only — use an SSH tunnel. Set `DB_BIND=127.0.0.1` in `.env` and redeploy to close
Postgres to other machines again.

The frontend is built with `VITE_API_BASE=/api`; no Norbiz-Web changes are needed.

## HTTPS

The certificate is a PEM pair in `~/norbiz/certs/` on the server: `tls.crt` (certificate, followed by any
intermediate CA certificates) and `tls.key` (unencrypted private key). It is never committed — git and
the images don't contain it.

- **nginx** is the only TLS endpoint: HTTPS on 443 for the SPA and `/api`; port 80 only redirects. It
  proxies to the app over plain HTTP — that hop never leaves the server's Docker network, which also
  carries the (plaintext) Postgres and Redis connections, so encrypting it alone would add nothing.
- **App** speaks plain HTTP and is published on the server's localhost only, so nothing reaches it except
  through nginx. Swagger UI: `https://<host>/api/swagger-ui.html` (nginx sends `X-Forwarded-Prefix: /api`).
  Direct access without nginx: `ssh -L 8080:localhost:8080 rdonor@10.18.18.18`, then http://localhost:8080.
- `deploy.sh` refuses to release (before stopping anything) if either file is missing, sets the
  directory and key to owner-only, and reloads nginx after the stack is up.

### Issuing the certificate (internal CA)

The server is on a private IP, so no public CA can issue for it. Instead, an internal CA signs the server
certificate and each client machine trusts that CA once. Run this on your machine, not the server,
from `~/norbiz-pki/` — never inside the repo: anything under `src/main/resources` is packaged into the WAR.

1. Create the CA (once). `ca.key` signs every future certificate — keep it off the server and out of git:

   ```bash
   openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
     -keyout ca.key -out ca.crt -subj "/CN=Norbiz Internal CA"
   ```

2. Create the server key and a signing request:

   ```bash
   openssl req -newkey rsa:2048 -nodes -keyout tls.key -out tls.csr -subj "/CN=10.18.18.18"
   ```

3. Sign it with the CA. The `subjectAltName` must match `PUBLIC_HOST` — browsers ignore the CN. Use
   `DNS:<name>` instead of `IP:<addr>` for a hostname:

   ```bash
   printf "subjectAltName=IP:10.18.18.18\nextendedKeyUsage=serverAuth\n" > san.ext
   openssl x509 -req -in tls.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
     -days 825 -sha256 -extfile san.ext -out tls.crt
   openssl x509 -in tls.crt -noout -text | grep -A1 "Subject Alternative Name"   # verify
   ```

   Keep the validity at 825 days or less; macOS and iOS reject longer-lived server certificates.

4. Trust `ca.crt` as a root on every client machine (macOS: Keychain Access → System → import, then
   set "Always Trust"; Windows: `certutil -addstore Root ca.crt`). Until then browsers warn.

To renew, or after `PUBLIC_HOST` changes, repeat steps 2–3 with the same CA — clients need no change.

### Installing

Install or replace the certificate from your machine, then release:

```bash
cd ~/norbiz-pki
scp tls.crt tls.key rdonor@10.18.18.18:~/norbiz/certs/
ssh rdonor@10.18.18.18 'SKIP_FETCH=1 ~/norbiz/deploy.sh'
```

Verify the server presents it and it chains to the CA (want `Verify return code: 0 (ok)`):

```bash
openssl s_client -connect 10.18.18.18:443 -CAfile ~/norbiz-pki/ca.crt </dev/null 2>/dev/null | grep "Verify return code"
```

## First-time server setup

First do the one-time host preparation in `docs/SERVER_REQUIREMENTS.md#host-preparation` (Docker from
Docker's apt repo, log rotation, swap). It includes step 2 below.

1. From your machine, enable key-based SSH (you'll type the server password once):
   ```bash
   ssh-copy-id rdonor@10.18.18.18
   ```
2. Let the user run Docker without sudo (on the server, then log out and back in):
   ```bash
   sudo usermod -aG docker rdonor
   ```
3. Copy the bootstrap files up and run it:
   ```bash
   scp deploy/bootstrap.sh deploy/lib.sh deploy/.env.example rdonor@10.18.18.18:/tmp/
   ssh rdonor@10.18.18.18 'bash /tmp/bootstrap.sh'
   ```
   It creates `~/norbiz`, writes `~/norbiz/.env` with a random `DB_PASSWORD` and `JWT_SECRET`, and
   prints two public keys.
4. Add each key on GitHub as a **read-only deploy key**: repo → Settings → Deploy keys → Add deploy key
   (`norbiz` key → Norbiz repo, `norbiz-web` key → Norbiz-Web repo; GitHub doesn't allow one key on two repos).
5. Run bootstrap again — it clones both repos and creates the `~/norbiz/deploy.sh` symlink:
   ```bash
   ssh rdonor@10.18.18.18 'bash /tmp/bootstrap.sh'
   ```
6. Copy the HTTPS certificate to `~/norbiz/certs/` (see [HTTPS](#https)). Review `~/norbiz/.env`
   (`PUBLIC_HOST`, `CORS_ALLOWED_ORIGINS`, `APP_MAX_HEAP` for the VM's RAM), then
   do the first release (below). The first build downloads all Maven/npm dependencies and takes a while.
   On the empty database `db/init.sql` creates the schema and the app's `DataInitializer` seeds it.

## Releasing

1. Commit and push Norbiz (including any new `db/migrations/NNN_*.sql` plus the matching `db/init.sql`
   change) and Norbiz-Web.
2. Run the release:
   ```bash
   ssh rdonor@10.18.18.18 '~/norbiz/deploy.sh'
   ```
   Optional refs (branch, tag or SHA): `~/norbiz/deploy.sh <norbiz-ref> <norbiz-web-ref>`.
   `SKIP_FETCH=1 ~/norbiz/deploy.sh` redeploys the current checkouts without pulling.

What `deploy.sh` does, in order:

1. Pulls both repos to the requested refs, then re-runs the freshly pulled `deploy.sh`.
2. Builds the `db` (postgres:17-alpine + pgvector, `Dockerfile.db`), `app`, `web` and `embedder` images —
   the running version keeps serving during the build — and checks the TLS certificate is in place.
3. `pg_dump`s the database to `~/norbiz/backups/norbiz-<timestamp>-<sha>.sql.gz`.
4. Stops the app, then starts db/redis (recreating db if its image changed). The observability container
   (`lgtm`) is never started by a release.
5. Runs `migrate.sh`. On failure that migration is rolled back, the previous app is restarted, and the
   release stops.
6. Starts the `embedder` without waiting on it (photo recognition is optional — see
   `docs/ITEM_RECOGNITION.md`), then the new app and web, and waits (up to 5 min) for `/actuator/health`;
   prints app logs if it doesn't come up. Reloads nginx so a new certificate takes effect.
7. Prunes dangling images and old build cache.

## Database changes

Write them as `db/migrations/NNN_description.sql` — rules in `db/migrations/README.md` (idempotent,
also folded into `init.sql`, no `BEGIN`/`COMMIT`). The prod app runs with `ddl-auto=validate`, so every
entity change needs a migration: a missing table or column stops the new app at startup (step 6 above
prints the Hibernate error).

When pointing a local backend at the prod database, start it with `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`
so it can't change the prod schema. Migrations are written to cope if it ran with `update` anyway
(`db/migrations/README.md`), but a branch that doesn't match prod then fails at startup instead of
creating tables there. Check state on the server:

```bash
ssh rdonor@10.18.18.18 '~/norbiz/src/Norbiz/deploy/migrate.sh --status'
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

The observability stack (`lgtm`: OTel Collector, Tempo, Loki, Prometheus, Grafana) is **not** started by
releases and OTLP export is off. To turn it on, set `OTEL_EXPORT_ENABLED=true` in `.env`, then:

```bash
$C --profile observability up -d lgtm
SKIP_FETCH=1 ~/norbiz/deploy.sh   # restart the app with export enabled
```

Grafana (traces/logs/metrics) from your machine:

```bash
ssh -L 3000:localhost:3000 rdonor@10.18.18.18
```

then open http://localhost:3000.

Postgres from another machine (e.g. DBeaver/psql): host `10.18.18.18`, port `5432`, database `norbiz`,
user `DB_USERNAME`, password `DB_PASSWORD` (`ssh rdonor@10.18.18.18 'grep ^DB_ ~/norbiz/.env'`).
This is the app's own superuser account — treat it accordingly.

The stack restarts by itself after a VM reboot (`restart: unless-stopped`, Docker enabled at boot).
