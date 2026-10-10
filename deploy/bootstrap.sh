#!/usr/bin/env bash
# One-time setup of a fresh deploy server (needs only Docker). Idempotent — run it again after
# each step it asks you to do. See docs/DEPLOYMENT.md.
#
#   scp deploy/bootstrap.sh deploy/lib.sh deploy/.env.example <user>@<server>:/tmp/
#   ssh <user>@<server> 'bash /tmp/bootstrap.sh'
set -euo pipefail
HERE="$(dirname "$(readlink -f "$0")")"
. "$HERE/lib.sh"

docker info >/dev/null 2>&1 || die "cannot talk to Docker as $(whoami) — run: sudo usermod -aG docker $(whoami), then log out and back in"

mkdir -p "$NORBIZ_HOME"/{src,backups,ssh}
chmod 700 "$NORBIZ_HOME/ssh"

# --- .env with generated secrets -------------------------------------------------------------------
if [ ! -f "$ENV_FILE" ]; then
  example="$HERE/.env.example"
  [ -f "$example" ] || example="$SRC_DIR/Norbiz/deploy/.env.example"
  [ -f "$example" ] || die ".env.example not found next to bootstrap.sh"
  db_pw="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  jwt="$(head -c 48 /dev/urandom | base64 -w0)"
  umask 077
  sed -e "s|^DB_PASSWORD=.*|DB_PASSWORD=$db_pw|" -e "s|^JWT_SECRET=.*|JWT_SECRET=$jwt|" "$example" > "$ENV_FILE"
  log "Created $ENV_FILE with random DB_PASSWORD and JWT_SECRET — review PUBLIC_HOST / CORS_ALLOWED_ORIGINS"
fi
load_env

# --- GitHub deploy keys (GitHub requires a separate key per repository) ---------------------------
keygen() {
  if command -v ssh-keygen >/dev/null; then
    ssh-keygen -q -t ed25519 -N "" -C "$2" -f "$1"
  else
    docker run --rm -v "$NORBIZ_HOME/ssh:/ssh" alpine:3.20 sh -ec \
      "apk add -q openssh-keygen && ssh-keygen -q -t ed25519 -N '' -C '$2' -f /ssh/$(basename "$1") && chown $(id -u):$(id -g) /ssh/$(basename "$1")*"
  fi
}
new_keys=""
for k in norbiz norbiz-web; do
  if [ ! -f "$NORBIZ_HOME/ssh/$k" ]; then
    keygen "$NORBIZ_HOME/ssh/$k" "norbiz-deploy-$k@$(hostname)"
    new_keys=1
  fi
done

cat > "$NORBIZ_HOME/ssh/config" <<'CFG'
Host github-norbiz
  HostName github.com
  User git
  IdentityFile /ssh/norbiz
  IdentitiesOnly yes
Host github-norbiz-web
  HostName github.com
  User git
  IdentityFile /ssh/norbiz-web
  IdentitiesOnly yes
Host *
  UserKnownHostsFile /ssh/known_hosts
  StrictHostKeyChecking yes
CFG
# GitHub's published SSH host key (https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/githubs-ssh-key-fingerprints)
echo "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl" > "$NORBIZ_HOME/ssh/known_hosts"
chmod 600 "$NORBIZ_HOME"/ssh/*

if [ -n "$new_keys" ]; then
  echo
  log "Add these as READ-ONLY deploy keys (GitHub repo -> Settings -> Deploy keys -> Add deploy key):"
  echo "  Norbiz:     $(cat "$NORBIZ_HOME/ssh/norbiz.pub")"
  echo "  Norbiz-Web: $(cat "$NORBIZ_HOME/ssh/norbiz-web.pub")"
  echo
  log "Then run this script again."
  exit 0
fi

# --- First checkout + deploy.sh symlink -------------------------------------------------------------
log "Cloning repositories"
git_sync "$NORBIZ_REPO" "${NORBIZ_REF:-master}" Norbiz || die "clone of Norbiz failed — is its deploy key added on GitHub?"
git_sync "$NORBIZ_WEB_REPO" "${NORBIZ_WEB_REF:-master}" Norbiz-Web || die "clone of Norbiz-Web failed — is its deploy key added on GitHub?"
ln -sfn "$SRC_DIR/Norbiz/deploy/deploy.sh" "$NORBIZ_HOME/deploy.sh"

log "Bootstrap complete. Review $ENV_FILE, then release with: ~/norbiz/deploy.sh"
