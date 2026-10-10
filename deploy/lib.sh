# Shared helpers for deploy.sh / migrate.sh / bootstrap.sh. Sourced, not executed.

NORBIZ_HOME="${NORBIZ_HOME:-$HOME/norbiz}"
SRC_DIR="$NORBIZ_HOME/src"
ENV_FILE="$NORBIZ_HOME/.env"
COMPOSE_FILE="$SRC_DIR/Norbiz/deploy/docker-compose.prod.yml"
GIT_IMAGE="alpine/git:v2.49.1"

log()  { printf '\033[1;34m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
warn() { printf '\033[1;33m[%s] WARN:\033[0m %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die()  { printf '\033[1;31m[%s] ERROR:\033[0m %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }

load_env() {
  [ -f "$ENV_FILE" ] || die "$ENV_FILE not found — run bootstrap.sh first"
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
}

compose() {
  docker compose -p "${COMPOSE_PROJECT:-norbiz}" --project-directory "$NORBIZ_HOME" --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" "$@"
}

# Runs git in a throwaway container (the server has no git). Runs as root so ssh works without a
# passwd entry, then hands the checkout back to the calling user.
# usage: git_sync <repo-url> <ref> <dest-dir-name under src/>
git_sync() {
  local repo="$1" ref="$2" name="$3"
  mkdir -p "$SRC_DIR"
  docker run --rm \
    -v "$NORBIZ_HOME/ssh:/ssh:ro" \
    -v "$SRC_DIR:/src" \
    -e GIT_SSH_COMMAND="ssh -F /ssh/config" \
    -e REPO="$repo" -e REF="$ref" -e NAME="$name" \
    -e OWNER="$(id -u):$(id -g)" \
    --entrypoint sh "$GIT_IMAGE" -ec '
      git config --global --add safe.directory "*"
      git config --global advice.detachedHead false
      if [ ! -d "/src/$NAME/.git" ]; then
        git clone --quiet "$REPO" "/src/$NAME"
      fi
      cd "/src/$NAME"
      git remote set-url origin "$REPO"
      git fetch --quiet --prune --tags --force origin
      # Branch name -> origin/<branch>; otherwise a tag or commit SHA.
      if git rev-parse --verify --quiet "origin/$REF^{commit}" >/dev/null; then
        target="origin/$REF"
      else
        target="$REF"
      fi
      git checkout --quiet --force --detach "$target"
      git reset --quiet --hard "$target"
      git clean --quiet -fdx
      chown -R "$OWNER" "/src/$NAME"
      echo "$NAME @ $(git rev-parse --short HEAD) ($REF): $(git log -1 --format=%s)"
    '
}

git_sha() {
  docker run --rm -v "$SRC_DIR/$1:/repo:ro" --entrypoint sh "$GIT_IMAGE" -c \
    'git config --global --add safe.directory "*"; git -C /repo rev-parse --short HEAD'
}
