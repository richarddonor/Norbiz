# Server Requirements

What a host needs to run the full Norbiz stack (backend, Norbiz-Web, Postgres, Redis, embedder) as
deployed by `deploy/`. Release mechanics are in `docs/DEPLOYMENT.md`; this doc covers what has to be in
place before the first `bootstrap.sh`, and the move of prod from the old VirtualBox VM (10.16.32.64) to
10.18.18.18.

## Summary

The host runs **only Docker**. Every build (Maven, npm, pip, pgvector) and every git checkout happens inside
containers, so no JDK, Node, Python, Postgres or git is needed on the host.

| | Minimum | Recommended |
|---|---|---|
| OS | Linux x86_64 that runs Docker Engine (Ubuntu 22.04 / 24.04 tested) | Ubuntu 24.04 LTS |
| CPU | 2 vCPU, **AVX2** (the CPU torch wheels in `embedder/` need it) | 4+ vCPU |
| RAM | 6 GiB + 4 GiB swap | 8 GiB + 4 GiB swap (required if the `lgtm` observability stack runs) |
| Disk | 40 GB | 100 GB+ (backups and item images grow) |
| Software | Docker Engine, Buildx plugin, Compose v2 plugin ≥ 2.17 | same, from Docker's apt repo |
| Network | outbound HTTPS + SSH to GitHub (below); inbound 80/443 | static IP; a DNS name if you have one |

Compose ≥ 2.17 because `deploy.sh` uses `compose up --wait --wait-timeout`, and the prod compose file uses
`profiles` and a top-level `name:`.

## Resource budget

From `deploy/docker-compose.prod.yml`, in steady state:

| Service | Image | Memory | Notes |
|---|---|---|---|
| `app` | `eclipse-temurin:25-jre` (built with `maven:3.9-eclipse-temurin-25`) | heap `APP_MAX_HEAP` (768m) + ~400 MB JVM overhead | port 8080 on localhost only |
| `db` | `postgres:17-alpine` + pgvector 0.8.0 (`Dockerfile.db`) | 300–500 MB | port 5432 on `DB_BIND` |
| `embedder` | `python:3.12-slim` + CPU torch 2.5.1 + CLIP ViT-B-32 | ~1 GB once the model is loaded | internal only, optional at runtime; `EMBEDDER_THREADS` (default 2) |
| `redis` | `redis:7-alpine` | ≤ 256 MB (`--maxmemory`) | internal only, no persistence |
| `web` | `nginx:alpine` (built with `node:22-alpine`) | < 50 MB | ports 80 / 443 |
| `lgtm` (opt-in) | `grafana/otel-lgtm:0.34.0` | 1–1.5 GB, heavy disk writes | only with `--profile observability` |
| git | `alpine/git:v2.49.1` | throwaway | used by `deploy/lib.sh` for clones |

That totals about 3 GiB in use. **Builds are the peak:** `compose build db app web embedder` runs the Maven
build, the npm build, and the torch install in parallel, so it can briefly use several GiB more. That is why
swap is required on a 6 GiB box. Docker images plus build cache take about 10–15 GB of disk.

## Network

Outbound, needed during bootstrap and every release:

| Host | Used for |
|---|---|
| `github.com` :22 (SSH) | cloning Norbiz / Norbiz-Web with deploy keys; pgvector source (HTTPS) |
| `download.docker.com` | installing Docker |
| `registry-1.docker.io` (Docker Hub) | base images |
| `repo.maven.apache.org` | Maven dependencies |
| `registry.npmjs.org` | npm dependencies |
| `download.pytorch.org`, `pypi.org`, `files.pythonhosted.org` | embedder Python packages |
| `huggingface.co` | CLIP weights (baked into the image at build time; the container runs offline) |
| `dl-cdn.alpinelinux.org` | Alpine packages (pgvector build) |

Inbound: `443` (SPA + `/api`), `80` (redirects to HTTPS), `22` (SSH), and `5432` (Postgres) unless
`DB_BIND=127.0.0.1`. The app (8080) and Grafana (3000) are bound to localhost and reached over an SSH tunnel.

If you turn on `ufw`, note that **ports Docker publishes bypass ufw rules**: Docker writes its own iptables
chains. To close Postgres, use `DB_BIND=127.0.0.1`, not a firewall rule.

## Host preparation

These steps run once, as a sudo user. Everything after this is `deploy/bootstrap.sh`.

```bash
# 1. Docker Engine + Buildx + Compose v2 from Docker's apt repo (Ubuntu's docker.io lacks the compose plugin)
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# 2. Rotate container logs. The default json-file driver never rotates, so the app's logs would eventually fill the disk.
echo '{ "log-driver": "json-file", "log-opts": { "max-size": "10m", "max-file": "3" } }' \
  | sudo tee /etc/docker/daemon.json >/dev/null
sudo systemctl enable --now docker containerd
sudo systemctl restart docker

# 3. Run Docker without sudo (log out and back in afterwards)
sudo usermod -aG docker "$USER"

# 4. 4 GiB swap (build peaks)
sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

Check:

```bash
docker compose version     # >= v2.17
docker info --format '{{.LoggingDriver}}'   # json-file, run without sudo after re-login
free -h                    # Swap: 4.0Gi
```

The stack comes back by itself after a reboot (`restart: unless-stopped`, Docker enabled at boot).

## Current prod host: 10.18.18.18

Assessed 2026-10-19. Host preparation done the same day: Docker 29.9.1, Compose v5.6.0, Buildx 0.38.0, log
rotation, 4 GiB swap, and `rdonor` in the `docker` group. Bootstrap done (repos cloned). First release done the same day at Norbiz `5669eb5` / Norbiz-Web `960fa30`
(the refs old prod runs), on an empty database. It took about 3½ minutes; steady-state memory is about 2.1 GiB in use.

| Item | State | Action |
|---|---|---|
| OS | Ubuntu 24.04.5 LTS, kernel 6.8, x86_64, KVM guest, hostname `norbiz-mik`. The hypervisor cloud-init data still says `norbitch`, so it is pinned by `preserve_hostname: true` in `/etc/cloud/cloud.cfg.d/99-norbiz-hostname.cfg` and a hard-coded `127.0.1.1` line in `/etc/cloud/templates/hosts.debian.tmpl` (original kept as `.orig`) | — |
| CPU | 16 vCPU, AMD Ryzen 9 5950X, AVX2 | `EMBEDDER_THREADS=4` set in `.env` |
| RAM | 5.8 GiB + 4 GiB `/swapfile` | keep `lgtm` off unless RAM goes to 8 GiB |
| Disk | 200 GB virtio, root ext4 193 GB (190 GB free) | — |
| Docker | installed from Docker's apt repo, enabled at boot, json-file logs 10m×3 | — |
| User | `rdonor`, passwordless sudo, key SSH works, in `docker` group | — |
| Outbound | every host in [Network](#network) reachable, including github.com:22 | — |
| Inbound | only SSH listening, ufw not active | — |
| Time | Asia/Manila, NTP synced | — |

## Migrating prod from 10.16.32.64

The old VM is a VirtualBox guest whose host keeps stalling it (soft lockups → ext4 `emergency_ro` →
Postgres dies). These steps move the database and item images to 10.18.18.18. `$C` is the compose prefix from
`docs/DEPLOYMENT.md#rollback`; run commands from `~/norbiz` on the host named.

1. **Prepare the new host.** Do [Host preparation](#host-preparation), then steps 1–5 of
   *First-time server setup* in `docs/DEPLOYMENT.md` as `rdonor@10.18.18.18`. Add the two new deploy keys on
   GitHub next to the old VM's (each repo can hold several).
2. **Certificate and settings.** Issue a certificate for `IP:10.18.18.18` with the **existing** CA in
   `~/norbiz-pki` (`docs/DEPLOYMENT.md#issuing-the-certificate-internal-ca`, steps 2–3). Clients already trust
   that CA, so they need no change. (Done 2026-10-19: issued in `~/norbiz-pki/10.18.18.18/`, valid to 2029-01-21,
   already copied to the server.) Copy it with
   `scp tls.crt tls.key rdonor@10.18.18.18:~/norbiz/certs/`. In the new `~/norbiz/.env` set
   `PUBLIC_HOST=10.18.18.18`, `CORS_ALLOWED_ORIGINS=https://10.18.18.18`, and `EMBEDDER_THREADS=4`.
   Optionally copy `JWT_SECRET` from the old `.env` so existing logins stay valid; otherwise users just log in again.
3. **First release at the ref prod runs.** Find it with
   `ssh vboxuser@10.16.32.64 'ls -t ~/norbiz/backups | head -1'` (the file name ends with the Norbiz SHA)
   or `git -C ~/norbiz/src/Norbiz log -1` there, then on the new host run
   `~/norbiz/deploy.sh <norbiz-ref> <web-ref>`. `init.sql` creates the schema on the empty volume.
   This first build takes a while.
4. **Freeze and export the old host** (downtime starts here):
   ```bash
   # on 10.16.32.64
   cd ~/norbiz && C="docker compose -p norbiz --project-directory . --env-file .env -f src/Norbiz/deploy/docker-compose.prod.yml"
   $C stop app web
   $C exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner' | gzip > /tmp/norbiz-final.sql.gz
   docker run --rm -v norbiz_item_images:/data:ro alpine:3.20 tar czf - -C /data . > /tmp/item-images.tgz
   ```
   Copy both to the new host through your machine:
   `scp vboxuser@10.16.32.64:/tmp/{norbiz-final.sql.gz,item-images.tgz} . && scp norbiz-final.sql.gz item-images.tgz rdonor@10.18.18.18:/tmp/`.
5. **Restore on the new host:**
   ```bash
   # on 10.18.18.18
   cd ~/norbiz && C="docker compose -p norbiz --project-directory . --env-file .env -f src/Norbiz/deploy/docker-compose.prod.yml"
   $C stop app
   $C exec -T db sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d norbiz -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"'
   gunzip -c /tmp/norbiz-final.sql.gz | $C exec -T db sh -c 'psql -v ON_ERROR_STOP=1 --single-transaction -U "$POSTGRES_USER" -d norbiz'
   docker run --rm -i -v norbiz_item_images:/data alpine:3.20 tar xzf - -C /data < /tmp/item-images.tgz
   SKIP_FETCH=1 ~/norbiz/deploy.sh
   ```
   The dump includes `schema_migrations`, the `vector` extension and `item_image_embeddings`, so
   `migrate.sh` finds nothing pending and photo search doesn't need a reindex. The image files keep their
   numeric owner, which is the same `appuser` because both hosts build the same Dockerfile.
6. **Verify:** do the checks below, then hand out `https://10.18.18.18`.
7. **Decommission the old host.** On 10.16.32.64 run `$C down`. This keeps the volumes, so the old data stays
   as a fallback for a while. Only one database should ever be live. Once you're sure, delete the old VM's
   deploy keys on GitHub.

### Verification

```bash
openssl s_client -connect 10.18.18.18:443 -CAfile ~/norbiz-pki/ca.crt </dev/null 2>/dev/null | grep "Verify return code"   # 0 (ok)
ssh rdonor@10.18.18.18 '~/norbiz/src/Norbiz/deploy/migrate.sh --status'   # nothing PENDING
```

Log in, open an item that has a photo, and compare a few row counts (items, inventory movements,
transactions) against the old database before decommissioning it.
