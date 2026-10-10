#!/usr/bin/env bash
# Applies pending db/migrations/NNN_*.sql files to the running db container, in name order.
# Each file runs in its own transaction together with its schema_migrations row, so a failing
# migration leaves nothing behind. See db/migrations/README.md for the rules.
#
# usage: migrate.sh            apply pending migrations
#        migrate.sh --status   list applied / pending, change nothing
set -euo pipefail
. "$(dirname "$(readlink -f "$0")")/lib.sh"

MIGRATIONS_DIR="$SRC_DIR/Norbiz/db/migrations"
NAME_RE='^[0-9]{3,}_[a-z0-9_]+\.sql$'

psql_db() {
  compose exec -T -e PGOPTIONS=-cclient_min_messages=warning db sh -c 'psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' psql "$@"
}

load_env
[ -n "$(compose ps --status running -q db)" ] || die "db container is not running"

psql_db -c "CREATE TABLE IF NOT EXISTS schema_migrations (
  version    VARCHAR(255) PRIMARY KEY,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);" >/dev/null

applied="$(psql_db -tA -c 'SELECT version FROM schema_migrations ORDER BY version')"

pending=()
shopt -s nullglob
for f in "$MIGRATIONS_DIR"/*.sql; do
  name="$(basename "$f")"
  [[ "$name" =~ $NAME_RE ]] || die "bad migration file name '$name' (expected NNN_lower_snake.sql)"
  if grep -qxF "$name" <<<"$applied"; then
    [ "${1:-}" = "--status" ] && echo "  applied  $name"
  else
    pending+=("$f")
    [ "${1:-}" = "--status" ] && echo "  PENDING  $name"
  fi
done

[ "${1:-}" = "--status" ] && exit 0

if [ ${#pending[@]} -eq 0 ]; then
  log "No pending migrations"
  exit 0
fi

for f in "${pending[@]}"; do
  name="$(basename "$f")"
  log "Applying migration $name"
  # File + bookkeeping row in one transaction (name is regex-validated above, safe to inline).
  { cat "$f"; printf "\nINSERT INTO schema_migrations(version) VALUES ('%s');\n" "$name"; } \
    | psql_db --single-transaction -f - \
    || die "migration $name failed and was rolled back — nothing from it was applied"
done
log "Applied ${#pending[@]} migration(s)"
