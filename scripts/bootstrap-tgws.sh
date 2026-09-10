#!/usr/bin/env bash
set -euo pipefail

REPO="https://github.com/L33kr/tg-ws-proxy-android.git"
REF="94d0620aff9a9e0dd08a1f9688a904da09df497e"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party/tg-ws-proxy-android"

mkdir -p "$(dirname "$DEST")"

if [[ -d "$DEST/.git" ]]; then
  git -C "$DEST" fetch --all --tags --prune
else
  rm -rf "$DEST"
  git clone --no-checkout "$REPO" "$DEST"
fi

git -C "$DEST" checkout --detach "$REF"
echo "TG WS dependency ready at $DEST"
echo "Pinned commit: $REF"
