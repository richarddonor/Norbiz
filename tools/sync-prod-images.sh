#!/usr/bin/env bash
# Mirrors production's norbiz_item_images Docker volume into the local item-image dir so a local
# backend (mvn spring-boot:run, app.item-image.upload-dir default ~/norbiz) can serve prod images.
# One-way, read-only on prod, merge-only locally (nothing local is deleted). Re-run to pick up new
# prod images; only changed files are transferred.
#
# The volume is only readable by root on the server, so the remote rsync runs inside a throwaway
# alpine container with the volume mounted read-only. Needs Homebrew rsync (macOS's openrsync does
# not interoperate with this): brew install rsync
set -euo pipefail

PROD_HOST="${PROD_HOST:-rdonor@10.18.18.18}"
VOLUME="${VOLUME:-norbiz_item_images}"
DEST="${1:-$HOME/norbiz}"
RSYNC="${RSYNC:-/opt/homebrew/bin/rsync}"

mkdir -p "$DEST"
exec "$RSYNC" -a --info=progress2,stats1 --exclude='logs/' \
  -e "ssh -o BatchMode=yes" \
  --rsync-path="docker run -i --rm -v $VOLUME:/d:ro alpine sh -c 'apk add -q --no-progress rsync >/dev/null 2>&1; exec rsync \"\$@\"' rsync" \
  "$PROD_HOST:/d/" "$DEST/"
