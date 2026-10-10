#!/usr/bin/env bash
# Release Norbiz on this server: pull both repos from GitHub, build images in Docker, back up the
# database, apply pending db/migrations, and restart the stack. See docs/DEPLOYMENT.md.
#
# usage: ~/norbiz/deploy.sh [norbiz-ref] [norbiz-web-ref]
#        refs default to NORBIZ_REF / NORBIZ_WEB_REF in ~/norbiz/.env (master); a branch, tag or SHA.
#        SKIP_FETCH=1 ~/norbiz/deploy.sh   rebuild/redeploy the current checkouts without pulling.
set -euo pipefail
SELF="$(readlink -f "$0")"
. "$(dirname "$SELF")/lib.sh"

load_env
REF="${1:-${NORBIZ_REF:-master}}"
WEB_REF="${2:-${NORBIZ_WEB_REF:-master}}"

# --- 1. Source -------------------------------------------------------------------------------
# After pulling, re-exec the freshly checked-out deploy.sh so this release's script changes apply.
if [ -z "${SKIP_FETCH:-}" ] && [ -z "${NORBIZ_DEPLOY_REEXEC:-}" ]; then
  log "Fetching sources"
  git_sync "$NORBIZ_REPO" "$REF" Norbiz
  git_sync "$NORBIZ_WEB_REPO" "$WEB_REF" Norbiz-Web
  NORBIZ_DEPLOY_REEXEC=1 exec "$SRC_DIR/Norbiz/deploy/deploy.sh" "$@"
fi

[ -d "$SRC_DIR/Norbiz" ] && [ -d "$SRC_DIR/Norbiz-Web" ] || die "sources missing under $SRC_DIR"
SHA="$(git_sha Norbiz)"
WEB_SHA="$(git_sha Norbiz-Web)"
log "Deploying Norbiz $SHA, Norbiz-Web $WEB_SHA"

# --- 2. Build (the running stack keeps serving meanwhile) ------------------------------------
log "Building images (first build downloads Maven/npm dependencies and takes a while)"
compose build app web

# --- 3. Back up the database before touching it ----------------------------------------------
BACKUP=""
if [ -n "$(compose ps --status running -q db)" ]; then
  mkdir -p "$NORBIZ_HOME/backups"
  BACKUP="$NORBIZ_HOME/backups/norbiz-$(date +%Y%m%d-%H%M%S)-$SHA.sql.gz"
  log "Backing up database to $BACKUP"
  compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner' \
    | gzip > "$BACKUP" || { rm -f "$BACKUP"; die "database backup failed — release aborted, nothing changed"; }
  # Keep only the newest BACKUP_KEEP backups.
  ls -1t "$NORBIZ_HOME"/backups/norbiz-*.sql.gz | tail -n +$(( ${BACKUP_KEEP:-10} + 1 )) | xargs -r rm -f
else
  log "Database not running yet (first deploy) — skipping backup"
fi

# --- 4. Infrastructure up, app stopped while the schema changes -------------------------------
log "Starting db, redis, lgtm"
compose up -d --wait db redis
compose up -d lgtm

APP_WAS_RUNNING=""
if [ -n "$(compose ps --status running -q app)" ]; then
  APP_WAS_RUNNING=1
  log "Stopping app for migrations"
  compose stop app
fi

# --- 5. Migrations -----------------------------------------------------------------------------
if ! "$SRC_DIR/Norbiz/deploy/migrate.sh"; then
  if [ -n "$APP_WAS_RUNNING" ]; then
    warn "Restarting the previous app version"
    compose start app || true
  fi
  die "release aborted at migrations.${BACKUP:+ Pre-release backup: $BACKUP}"
fi

# --- 6. New app + web ----------------------------------------------------------------------------
log "Starting app and web (waiting for the app health check)"
if ! compose up -d --wait --wait-timeout 300 app web; then
  compose logs --tail 80 app >&2 || true
  die "app did not become healthy. Migrations (if any) were applied.${BACKUP:+ Pre-release backup: $BACKUP}
  Roll back code: ~/norbiz/deploy.sh <previous-ref>"
fi

# --- 7. Housekeeping -----------------------------------------------------------------------------
docker image prune -f >/dev/null
docker builder prune -f --filter until=168h >/dev/null || true

compose ps
log "Released Norbiz $SHA / Norbiz-Web $WEB_SHA — http://${PUBLIC_HOST:-<server>}${WEB_PORT:+:$WEB_PORT}"
