#!/usr/bin/env bash
# =============================================================================================
# Legacy jbsKarutora -> Norbiz migration: the one command for rehearsals and the real cutover.
#
#   ./run_migration.sh --confirm-reset <target db> [--skip-extract] [--skip-images] [--images-limit N]
#
#   1. preflight  — target DB reachable and named by --confirm-reset, Norbiz app NOT connected,
#                   schema + seed data present, legacy SQL Server reachable
#   2. backup     — pg_dump of the target DB to backups/<db>-<timestamp>.dump (always)
#   3. migrate    — extract + reset + load + reconcile + report (migrate.py); stops on a failed check
#   4. images     — item pictures into ITEM_IMAGE_UPLOAD_DIR (images.py --clean)
#   5. redis      — flush the query cache (bulk SQL bypasses cache invalidation)
#
# Configuration comes from the environment, normally ./config.env (see config.env.example). The
# backup uses pg_dump if it is on PATH, else `docker exec $PG_DOCKER_CONTAINER pg_dump` for a localhost
# PG_DSN, else `docker run postgres:17-alpine pg_dump` against PG_DSN (remote targets).
# Afterwards: start Norbiz and let DataInitializer finish (see docs/LEGACY_MIGRATION.md).
# =============================================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

CONFIRM=""; SKIP_EXTRACT=""; SKIP_IMAGES=""; IMAGES_LIMIT=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --confirm-reset) CONFIRM="${2:-}"; shift 2 ;;
        --skip-extract)  SKIP_EXTRACT="--skip-extract"; shift ;;
        --skip-images)   SKIP_IMAGES=1; shift ;;
        --images-limit)  IMAGES_LIMIT="--limit ${2:-}"; shift 2 ;;
        -h|--help)       sed -n '2,19p' "$0"; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done
[[ -n "$CONFIRM" ]] || { echo "--confirm-reset <target db name> is required: this run wipes that database's Norbiz data." >&2; exit 2; }

if [[ -f config.env ]]; then set -a; . ./config.env; set +a; fi
: "${PG_DSN:?PG_DSN is not set (see config.env.example)}"
PY="$HERE/.venv/bin/python"
[[ -x "$PY" ]] || { echo "Missing $PY — run: python3 -m venv .venv && .venv/bin/pip install -r requirements.txt" >&2; exit 2; }

STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p backups reports
LOG="reports/run-$STAMP.log"
exec > >(tee -a "$LOG") 2>&1
step() { echo; echo "=== $* ($(date +%H:%M:%S))"; }
started=$SECONDS

step "1/5 preflight"
"$PY" cutover.py preflight --confirm-reset "$CONFIRM"

step "2/5 backup"
DB="$("$PY" cutover.py dsn dbname)"
DB_HOST="$("$PY" cutover.py dsn host)"
BACKUP="backups/$DB-$STAMP.dump"
if command -v pg_dump >/dev/null 2>&1; then
    pg_dump --format=custom --file="$BACKUP" "$PG_DSN"
elif [[ -n "${PG_DOCKER_CONTAINER:-}" && "$DB_HOST" =~ ^(localhost|127\.0\.0\.1|::1)$ ]]; then
    # Local target: dump from inside its own container (only valid when PG_DSN points at this machine).
    docker exec "$PG_DOCKER_CONTAINER" pg_dump --format=custom -U "$("$PY" cutover.py dsn user)" -d "$DB" > "$BACKUP"
elif command -v docker >/dev/null 2>&1; then
    # Remote target: run a throwaway pg_dump client (same major version as the server) against PG_DSN.
    docker run --rm postgres:17-alpine pg_dump --format=custom "$PG_DSN" > "$BACKUP"
else
    echo "No pg_dump on PATH and no docker to run one — refusing to reset without a backup." >&2
    exit 1
fi
[[ -s "$BACKUP" ]] || { echo "Backup $BACKUP is empty — stopping." >&2; exit 1; }
echo "Backup: $BACKUP ($(du -h "$BACKUP" | cut -f1))"
echo "Restore with: pg_restore --clean --if-exists -d <db> $BACKUP"

step "3/5 migrate"
"$PY" migrate.py --confirm-reset "$CONFIRM" $SKIP_EXTRACT --report-dir reports

if [[ -z "$SKIP_IMAGES" ]]; then
    step "4/5 item pictures"
    # shellcheck disable=SC2086
    "$PY" images.py --clean $IMAGES_LIMIT
else
    step "4/5 item pictures — skipped (--skip-images)"
fi

step "5/5 flush Redis"
"$PY" cutover.py flush-redis

echo
echo "Migration finished in $(( (SECONDS - started) / 60 )) min. Log: $LOG"
echo "Next: start Norbiz, wait for DataInitializer (the super_admin user's company list includes the migrated company),"
echo "then review the report in reports/ and spot-check before go-live."
