#!/bin/sh
# Node-free SessionStart bootstrap for the shipsmooth jlink runtime.
#
# Invoked by the plugin's SessionStart hook as:
#   sh ".../hooks/install-shipsmooth.sh" <plugin-name> <version>
#
# Strict POSIX sh using only the stock-macOS toolset (sh, curl, unzip, uname,
# chmod, mktemp, mv): detect platform, skip if already installed, download the
# release zip, unzip (Info-ZIP restores the stored +x on runtime/lib/jspawnhelper
# that OpenJ9 needs to spawn subprocesses), force-chmod the launcher as a
# backstop, then atomically mv into place. Mirrors session-start.ts.
#
# Env override (tests): SS_URL_BASE replaces the GitHub releases base URL.
set -eu

NAME="${1:?usage: install-shipsmooth.sh <plugin-name> <version>}"
VERSION="${2:?usage: install-shipsmooth.sh <plugin-name> <version>}"

CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/$NAME"
URL_BASE="${SS_URL_BASE:-https://github.com/bitkentech/shipsmooth/releases/download/v$VERSION}"

log() { printf '%s: %s\n' "$NAME" "$1" >&2; }
die() { log "$1"; exit 1; }

os=$(uname -s)
arch=$(uname -m)
case "$os" in
  Darwin) os=darwin ;;
  Linux)  os=linux ;;
  *) die "unsupported OS: $os" ;;
esac
case "$arch" in
  arm64|aarch64) arch=arm64 ;;
  x86_64|amd64)  arch=x64 ;;
  *) die "unsupported arch: $arch" ;;
esac
PLATFORM="$os-$arch"

RUNTIME_DIR="$CACHE_DIR/runtime-$VERSION"
BIN="$RUNTIME_DIR/bin/shipsmooth"
if [ -x "$BIN" ]; then
  log "runtime $VERSION already installed at $RUNTIME_DIR"
  exit 0
fi

case "$PLATFORM" in
  linux-x64|darwin-x64|darwin-arm64) : ;;
  *) die "platform $PLATFORM is not supported" ;;
esac

TMP=$(mktemp -d "${TMPDIR:-/tmp}/$NAME-XXXXXX")
EXTRACT_DIR="$RUNTIME_DIR.tmp"
trap 'rm -rf "$TMP" "$EXTRACT_DIR"' EXIT INT TERM

ZIP="$TMP/runtime.zip"
URL="$URL_BASE/$NAME-$VERSION-$PLATFORM.zip"
log "downloading runtime from $URL"
curl -fsSL --retry 2 --retry-delay 1 -A "$NAME-runtime-installer" -o "$ZIP" "$URL" \
  || die "failed to download $URL"

rm -rf "$EXTRACT_DIR"
mkdir -p "$EXTRACT_DIR"
unzip -q "$ZIP" -d "$EXTRACT_DIR"

EXTRACTED_BIN="$EXTRACT_DIR/bin/shipsmooth"
[ -f "$EXTRACTED_BIN" ] || die "extracted archive is missing bin/shipsmooth"
chmod 0755 "$EXTRACTED_BIN"

rm -rf "$RUNTIME_DIR"
mv "$EXTRACT_DIR" "$RUNTIME_DIR"
log "runtime $VERSION installed at $RUNTIME_DIR"
