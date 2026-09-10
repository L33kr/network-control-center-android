#!/usr/bin/env bash
set -euo pipefail

BYEDPI_REPO="https://github.com/hufrea/byedpi.git"
BYEDPI_REF="ba532298de7b28cfe854aea83d061369d13ca290"
HEV_REPO="https://github.com/heiher/hev-socks5-tunnel.git"
HEV_REF="941c758101385d145c66210ac88991daaf27d4b6"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
THIRD_PARTY="$ROOT/third_party"

checkout_repo() {
  local repo="$1"
  local ref="$2"
  local dest="$3"
  local recursive="${4:-false}"

  mkdir -p "$(dirname "$dest")"
  if [[ -d "$dest/.git" ]]; then
    git -C "$dest" fetch --all --tags --prune
  else
    rm -rf "$dest"
    git clone --no-checkout "$repo" "$dest"
  fi
  git -C "$dest" checkout --detach "$ref"
  if [[ "$recursive" == "true" ]]; then
    git -C "$dest" submodule update --init --recursive
  fi
}

checkout_repo "$BYEDPI_REPO" "$BYEDPI_REF" "$THIRD_PARTY/byedpi"
checkout_repo "$HEV_REPO" "$HEV_REF" "$THIRD_PARTY/hev-socks5-tunnel" true

echo "ByeDPI: $BYEDPI_REF"
echo "hev-socks5-tunnel: $HEV_REF"
